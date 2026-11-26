#!/usr/bin/env node
// Code graph generator — reads dependencies from the real sources and writes Mermaid graphs to
// docs/code-graph/generated.md (English) and docs/code-graph/generated_kor.md (Korean).
//
//   node scripts/code-graph.mjs            # generate (run again after changing code)
//   node scripts/code-graph.mjs --check    # verify both generated files are current (exit 1 if not)
//
// 1) Backend module dependencies — ESM imports (backend/src)
// 2) Local model-manager module dependencies — ESM imports (ml/model-manager), including shared backend modules
// 3) Android app dependencies — whether a Kotlin file references another file's top-level declarations
//    (class/object/interface/fun). Same-package code has no imports, so name references are used —
//    comments and strings are stripped first and names are matched on word boundaries.
// 4) API map — master/farmer ApiService (Retrofit) endpoints → the backend route file that serves them
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'docs/code-graph');
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');

// ---------- strings (en = default, ko = Korean) ----------

const L = {
  en: {
    out: 'generated.md',
    shared: '(shared)',
    backendTitle: 'Backend (backend/src)',
    backendNote: '`db.js` (SQLite connection + schema) is imported by almost every module, and `app.js` mounts every `routes/*.js` at `/api/<name>` — both kinds of arrows are omitted (mount paths are in §4 API map).',
    mmTitle: 'Local model-manager (ml/model-manager)',
    mmNote: 'The "(shared)" group holds backend modules the local tool reuses as-is — model math (`ojp1.js`) and the built-in model definitions (`defs.js`).',
    masterTitle: 'Master app',
    farmerTitle: 'Farmer app',
    packages: 'packages',
    files: 'files',
    pkgNote: 'Arrow number = number of (file → file) references from that package into files of the target package. Parentheses = file count.',
    hubNote: (list) => `★ hub files (referenced by most files — arrows omitted): ${list || 'none'}`,
    hubItem: (name, n) => `\`${name}\` (referenced by ${n} files)`,
    tableHead: '| App | Function | Call | Backend route file |',
    none: '**none**',
    masterNode: 'Master app',
    farmerNode: 'Farmer app',
    doc: (x) => `# Code graph (generated)

> **This file is generated from the sources by \`node scripts/code-graph.mjs\` — do not edit it by hand.**
> Hand-written flows and the data model: [README.md](README.md). 한국어: [generated_kor.md](generated_kor.md)

| Graph | Size |
|---|---|
| Backend modules | ${x.backend.nodes} modules, ${x.backend.edges} imports (excluding imports of db.js and app.js router mounts) |
| Local model-manager modules | ${x.mm.nodes} modules, ${x.mm.edges} imports |
| Master app | ${x.master.files} Kotlin files, ${x.master.edges} file-to-file references |
| Farmer app | ${x.farmer.files} Kotlin files, ${x.farmer.edges} file-to-file references |
| API map | ${x.api.routes} backend routes, ${x.api.unmatched.length} app calls without a matching route |

## 1. Backend

${x.backend.md}

## 2. Local model-manager

${x.mm.md}

## 3. Android apps

Kotlin code in the same package uses each other without imports, so **a reference to another file's top-level declaration (class / object / interface / fun) by name** counts as a dependency (comments and strings excluded).

${x.master.md}

${x.farmer.md}

## 4. API map — backend routes the apps call

Endpoints of the Retrofit \`ApiService\` matched against the mounts in \`backend/src/app.js\` plus the paths in each route file. Arrow number = endpoint count.
(Paths called outside Retrofit — the Farmer's SSE command stream and model file downloads — are not included.)

${x.api.graph}

${x.api.unmatched.length ? `**Calls with no backend route:** ${x.api.unmatched.map((u) => `\`${u}\``).join(', ')}\n` : 'Every app call matches a backend route.\n'}
<details>
<summary>Full endpoint table</summary>

${x.api.table}

</details>
`,
  },
  ko: {
    out: 'generated_kor.md',
    shared: '(공유)',
    backendTitle: '백엔드 (backend/src)',
    backendNote: '`db.js`(SQLite 연결·스키마)는 거의 모든 모듈이 import 하고, `app.js` 는 모든 `routes/*.js` 를 `/api/<이름>` 에 마운트한다 — 이 두 가지 화살표는 생략했다 (마운트 경로는 §4 API 지도).',
    mmTitle: '로컬 model-manager (ml/model-manager)',
    mmNote: '"(공유)" 그룹은 로컬 도구가 그대로 가져다 쓰는 백엔드 모듈 — 모델 연산(`ojp1.js`)과 내장 모델 정의(`defs.js`).',
    masterTitle: 'Master 앱',
    farmerTitle: 'Farmer 앱',
    packages: '패키지',
    files: '파일',
    pkgNote: '화살표 숫자 = 그 패키지에서 대상 패키지의 파일을 참조하는 (파일→파일) 관계 수. 괄호 = 파일 수.',
    hubNote: (list) => `★ 허브 파일(대부분의 파일이 참조 — 화살표 생략): ${list || '없음'}`,
    hubItem: (name, n) => `\`${name}\` (${n}개 파일이 참조)`,
    tableHead: '| 앱 | 함수 | 호출 | 백엔드 라우트 파일 |',
    none: '**없음**',
    masterNode: 'Master 앱',
    farmerNode: 'Farmer 앱',
    doc: (x) => `# 코드 그래프 (자동 생성)

> **이 파일은 \`node scripts/code-graph.mjs\` 가 소스에서 만든다 — 직접 고치지 말 것.**
> 사람이 정리한 흐름·데이터 모델은 [README_kor.md](README_kor.md). English: [generated.md](generated.md)

| 그래프 | 규모 |
|---|---|
| 백엔드 모듈 | ${x.backend.nodes} 모듈, import ${x.backend.edges}개 (db.js 로 가는 것·app.js 라우터 마운트 제외) |
| 로컬 model-manager 모듈 | ${x.mm.nodes} 모듈, import ${x.mm.edges}개 |
| Master 앱 | Kotlin ${x.master.files} 파일, 파일 간 참조 ${x.master.edges}개 |
| Farmer 앱 | Kotlin ${x.farmer.files} 파일, 파일 간 참조 ${x.farmer.edges}개 |
| API 지도 | 백엔드 라우트 ${x.api.routes}개, 앱 → 라우트 매칭 실패 ${x.api.unmatched.length}개 |

## 1. 백엔드

${x.backend.md}

## 2. 로컬 model-manager

${x.mm.md}

## 3. Android 앱

Kotlin 은 같은 패키지끼리 import 없이 쓰므로, **다른 파일의 최상위 선언(class / object / interface / fun) 이름을 참조하면** 의존으로 본다 (주석·문자열 제외).

${x.master.md}

${x.farmer.md}

## 4. API 지도 — 앱이 부르는 백엔드 라우트

Retrofit \`ApiService\` 의 엔드포인트를 \`backend/src/app.js\` 의 마운트 + 라우트 파일 경로와 맞춘 결과. 화살표 숫자 = 엔드포인트 수.
(Farmer 의 SSE 명령 스트림·모델 파일 다운로드처럼 Retrofit 밖에서 부르는 경로는 포함하지 않는다.)

${x.api.graph}

${x.api.unmatched.length ? `**백엔드에서 찾지 못한 호출:** ${x.api.unmatched.map((u) => `\`${u}\``).join(', ')}\n` : '모든 앱 호출이 백엔드 라우트와 맞는다.\n'}
<details>
<summary>엔드포인트 전체 표</summary>

${x.api.table}

</details>
`,
  },
};

function walk(dir, exts, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'build' || e.name.startsWith('.')) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, exts, out);
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p);
  }
  return out.sort();
}

const id = (s) => s.replace(/[^A-Za-z0-9]/g, '_');

// ---------- 1, 2) JS import graph ----------

const IMPORT_RE = /(?:^|\n)\s*import\s+(?:[^'";]*?\s+from\s+)?['"]([^'"]+)['"]|import\(\s*[`'"]([^`'"]+)[`'"]\s*\)/g;

function jsImports(file) {
  const src = fs.readFileSync(file, 'utf8');
  const out = [];
  for (const m of src.matchAll(IMPORT_RE)) {
    const spec = m[1] || m[2];
    if (!spec || !spec.startsWith('.')) continue; // skip external packages
    const target = path.resolve(path.dirname(file), spec.replace(/\$\{[^}]+\}/g, ''));
    if (fs.existsSync(target) && fs.statSync(target).isFile()) out.push(target);
  }
  return [...new Set(out)];
}

/** file → group (folder) name */
function groupOf(file, base) {
  const r = path.relative(base, path.dirname(file)).split(path.sep).join('/');
  return r || '.';
}

/** Collects the import graph once; render() draws it with language-specific labels. */
function jsGraph({ key, base, files, hide = [], hideFrom = [] }) {
  const all = new Set(files);
  const edges = [];
  for (const f of files) {
    if (hideFrom.some((h) => rel(f).endsWith(h))) continue; // compare '/'-separated paths (OS independent)
    for (const t of jsImports(f)) {
      if (hide.some((h) => rel(t).endsWith(h))) continue;
      edges.push([f, t]);
      all.add(t);
    }
  }
  const render = ({ title, note, shared }) => {
    const groups = new Map();
    for (const f of all) {
      const inside = f.startsWith(base);
      const g = inside ? groupOf(f, base) : `${shared} ${rel(path.dirname(f))}`;
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(f);
    }
    const lines = ['```mermaid', 'flowchart LR'];
    for (const [g, fs_] of [...groups.entries()].sort()) {
      lines.push(`  subgraph ${id(`g_${key}_${g}`)}["${g}"]`);
      for (const f of fs_.sort()) lines.push(`    ${id(rel(f))}["${path.basename(f)}"]`);
      lines.push('  end');
    }
    for (const [a, b] of edges) lines.push(`  ${id(rel(a))} --> ${id(rel(b))}`);
    lines.push('```');
    return [`### ${title}`, '', note, '', ...lines].join('\n');
  };
  return { render, nodes: all.size, edges: edges.length };
}

// ---------- 3) Kotlin reference graph ----------

function stripKotlin(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\\n])*"/g, '""');
}

const DECL_RE = /^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:internal|public|data|sealed|enum|abstract|open|annotation|value|inline)\s+)*(?:class|object|interface|fun)\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)/;

function kotlinGraph({ key, srcDir, pkgPrefix }) {
  const files = walk(srcDir, ['.kt']);
  const decls = new Map(); // file -> names
  for (const f of files) {
    const names = new Set();
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      if (/^\s/.test(line) || line.startsWith('private ')) continue; // top-level public declarations only
      const m = DECL_RE.exec(line);
      if (m && m[1].length >= 4) names.add(m[1]);
    }
    decls.set(f, names);
  }
  const owner = new Map(); // name -> file (names declared in several files are ambiguous — dropped)
  const dup = new Set();
  for (const [f, names] of decls) for (const n of names) { if (owner.has(n)) dup.add(n); owner.set(n, f); }
  for (const n of dup) owner.delete(n);

  const edges = new Map(); // "a|b" -> number of referenced names
  for (const f of files) {
    const body = stripKotlin(fs.readFileSync(f, 'utf8'));
    const words = new Set(body.match(/\b[A-Za-z_]\w*\b/g));
    for (const w of words) {
      const t = owner.get(w);
      if (!t || t === f) continue;
      const k = `${f}|${t}`;
      edges.set(k, (edges.get(k) || 0) + 1);
    }
  }
  const pkgOf = (f) => path.relative(srcDir, path.dirname(f)).split(path.sep).join('.') || '(root)';

  // package-level summary
  const pkgEdges = new Map();
  for (const k of edges.keys()) {
    const [a, b] = k.split('|');
    const pa = pkgOf(a); const pb = pkgOf(b);
    if (pa === pb) continue;
    pkgEdges.set(`${pa}|${pb}`, (pkgEdges.get(`${pa}|${pb}`) || 0) + 1);
  }
  const pkgs = [...new Set(files.map(pkgOf))].sort();
  const pkgCount = (p) => files.filter((f) => pkgOf(f) === p).length;
  const pkgLines = ['```mermaid', 'flowchart LR'];
  for (const p of pkgs) pkgLines.push(`  ${id(`${key}_p_${p}`)}["${pkgPrefix}${p === '(root)' ? '' : `.${p}`} (${pkgCount(p)})"]`);
  for (const [k, n] of [...pkgEdges.entries()].sort()) {
    const [a, b] = k.split('|');
    pkgLines.push(`  ${id(`${key}_p_${a}`)} -- ${n} --> ${id(`${key}_p_${b}`)}`);
  }
  pkgLines.push('```');

  // file level — one subgraph per package. Hubs used by almost every file (models, API, strings, theme) are marked instead
  const inDeg = new Map();
  for (const k of edges.keys()) { const t = k.split('|')[1]; inDeg.set(t, (inDeg.get(t) || 0) + 1); }
  const hubs = new Set([...inDeg.entries()].filter(([, n]) => n >= Math.max(6, files.length * 0.3)).map(([f]) => f));
  const fileLines = ['```mermaid', 'flowchart LR'];
  for (const p of pkgs) {
    fileLines.push(`  subgraph ${id(`${key}_s_${p}`)}["${p}"]`);
    for (const f of files.filter((x) => pkgOf(x) === p)) {
      fileLines.push(`    ${id(rel(f))}["${path.basename(f, '.kt')}${hubs.has(f) ? ' ★' : ''}"]`);
    }
    fileLines.push('  end');
  }
  let shown = 0;
  for (const k of [...edges.keys()].sort()) {
    const [a, b] = k.split('|');
    if (hubs.has(b)) continue;
    fileLines.push(`  ${id(rel(a))} --> ${id(rel(b))}`);
    shown++;
  }
  fileLines.push('```');
  const render = ({ title, t }) => {
    const hubList = [...hubs].map((f) => t.hubItem(path.basename(f), inDeg.get(f))).join(', ');
    return [
      `### ${title} — ${t.packages}`, '',
      t.pkgNote, '',
      ...pkgLines, '',
      `### ${title} — ${t.files}`, '',
      t.hubNote(hubList), '',
      ...fileLines,
    ].join('\n');
  };
  return { render, files: files.length, edges: edges.size, shown };
}

// ---------- 4) API map ----------

function backendRoutes() {
  const appSrc = fs.readFileSync(path.join(ROOT, 'backend/src/app.js'), 'utf8');
  const importOf = new Map();
  for (const m of appSrc.matchAll(/import\s+(\w+)(?:,\s*\{[^}]*\})?\s+from\s+'\.\/routes\/([\w-]+)\.js'/g)) importOf.set(m[1], m[2]);
  const mounts = [];
  for (const m of appSrc.matchAll(/app\.use\('([^']+)',\s*(\w+)\)/g)) {
    if (importOf.has(m[2])) mounts.push({ prefix: m[1], file: importOf.get(m[2]) });
  }
  const routes = [];
  // paths attached to app.js directly (e.g. GET /health)
  for (const m of appSrc.matchAll(/\bapp\.(get|post|put|delete)\(\s*'(\/[^']*)'/g)) {
    if (m[2].startsWith('/api/') || m[2] === '/health') routes.push({ method: m[1].toUpperCase(), path: m[2], file: 'app' });
  }
  for (const { prefix, file } of mounts) {
    const src = fs.readFileSync(path.join(ROOT, `backend/src/routes/${file}.js`), 'utf8');
    for (const m of src.matchAll(/\br\.(get|post|put|delete|patch)\(\s*'([^']+)'/g)) {
      routes.push({ method: m[1].toUpperCase(), path: (prefix + (m[2] === '/' ? '' : m[2])).replace(/\/+$/, '') || '/', file });
    }
  }
  return routes;
}

const segs = (p) => p.replace(/^\/+/, '').split('?')[0].split('/').filter(Boolean);
const isParam = (s) => s.startsWith(':') || /^\{.+\}$/.test(s);

function matchRoute(routes, method, clientPath) {
  const c = segs(clientPath);
  const cands = routes.filter((r) => r.method === method && segs(r.path).length === c.length
    && segs(r.path).every((s, i) => isParam(s) || isParam(c[i]) || s === c[i]));
  // prefer the route with more fixed segments (/plant/:id over /:userId)
  cands.sort((a, b) => segs(b.path).filter((s) => !isParam(s)).length - segs(a.path).filter((s) => !isParam(s)).length);
  return cands[0] || null;
}

function apiCalls(file) {
  const src = fs.readFileSync(file, 'utf8');
  const out = [];
  for (const m of src.matchAll(/@(GET|POST|PUT|DELETE|PATCH)\("([^"]+)"\)[\s\S]*?(?:suspend\s+)?fun\s+(\w+)/g)) {
    out.push({ method: m[1], path: `/${m[2]}`, fn: m[3] });
  }
  return out;
}

function apiMap() {
  const routes = backendRoutes();
  const apps = [
    ['master', 'android/master/app/src/main/java/com/oojoo/farm/master/network/ApiService.kt'],
    ['farmer', 'android/slave/app/src/main/java/com/oojoo/farm/slave/network/ApiService.kt'],
  ];
  const rows = []; // { app, fn, call, file | null }
  const counts = new Map(); // app|file -> n
  const unmatched = [];
  for (const [app, f] of apps) {
    for (const c of apiCalls(path.join(ROOT, f))) {
      const r = matchRoute(routes, c.method, c.path);
      rows.push({ app, fn: c.fn, call: `${c.method} ${c.path}`, file: r ? (r.file === 'app' ? 'app.js' : `routes/${r.file}.js`) : null });
      if (r) counts.set(`${app}|${r.file}`, (counts.get(`${app}|${r.file}`) || 0) + 1);
      else unmatched.push(`${app} ${c.method} ${c.path}`);
    }
  }
  const files = [...new Set([...counts.keys()].map((k) => k.split('|')[1]))].sort();
  const render = (t) => {
    const table = [t.tableHead, '|---|---|---|---|'];
    for (const r of rows) table.push(`| ${r.app} | \`${r.fn}\` | \`${r.call}\` | ${r.file ? `\`${r.file}\`` : t.none} |`);
    const g = ['```mermaid', 'flowchart LR', `  master(["${t.masterNode}"])`, `  farmer(["${t.farmerNode}"])`, '  subgraph routes["backend/src/routes"]'];
    for (const f of files.filter((x) => x !== 'app')) g.push(`    r_${id(f)}["${f}.js"]`);
    g.push('  end');
    if (files.includes('app')) g.push('  r_app["app.js (/health)"]');
    for (const [k, n] of [...counts.entries()].sort()) {
      const [app, f] = k.split('|');
      g.push(`  ${app} -- ${n} --> r_${id(f)}`);
    }
    g.push('```');
    return { graph: g.join('\n'), table: table.join('\n') };
  };
  return { render, unmatched, routes: routes.length };
}

// ---------- generate ----------

const backend = jsGraph({
  key: 'backend',
  base: path.join(ROOT, 'backend/src'),
  files: walk(path.join(ROOT, 'backend/src'), ['.js']),
  hide: ['/db.js'],
  hideFrom: ['/app.js', '/server.js'],
});
const mm = jsGraph({
  key: 'mm',
  base: path.join(ROOT, 'ml/model-manager'),
  files: walk(path.join(ROOT, 'ml/model-manager'), ['.js', '.mjs']).filter((f) => !f.includes(`${path.sep}test${path.sep}`)),
});
const master = kotlinGraph({ key: 'master', srcDir: path.join(ROOT, 'android/master/app/src/main/java/com/oojoo/farm/master'), pkgPrefix: 'master' });
const farmer = kotlinGraph({ key: 'farmer', srcDir: path.join(ROOT, 'android/slave/app/src/main/java/com/oojoo/farm/slave'), pkgPrefix: 'slave' });
const api = apiMap();

const outputs = Object.values(L).map((t) => {
  const a = api.render(t);
  const md = t.doc({
    backend: { ...backend, md: backend.render({ title: t.backendTitle, note: t.backendNote, shared: t.shared }) },
    mm: { ...mm, md: mm.render({ title: t.mmTitle, note: t.mmNote, shared: t.shared }) },
    master: { ...master, md: master.render({ title: t.masterTitle, t }) },
    farmer: { ...farmer, md: farmer.render({ title: t.farmerTitle, t }) },
    api: { ...api, ...a },
  });
  return { file: path.join(OUT_DIR, t.out), md };
});

if (process.argv.includes('--check')) {
  // compare with LF line endings (Windows checkouts with core.autocrlf=true have CRLF)
  const stale = outputs.filter(({ file, md }) => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : '') !== md);
  if (stale.length) {
    console.error(`${stale.map((o) => rel(o.file)).join(', ')} out of date — run: node scripts/code-graph.mjs`);
    process.exit(1);
  }
  console.log('code graph up to date');
} else {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  for (const { file, md } of outputs) fs.writeFileSync(file, md);
  console.log(`wrote ${outputs.map((o) => rel(o.file)).join(', ')} — backend ${backend.nodes}/${backend.edges}, model-manager ${mm.nodes}/${mm.edges}, master ${master.files}/${master.edges} (shown ${master.shown}), farmer ${farmer.files}/${farmer.edges} (shown ${farmer.shown}), api routes ${api.routes}, unmatched ${api.unmatched.length}`);
  if (api.unmatched.length) console.log('unmatched:', api.unmatched.join(' | '));
}
