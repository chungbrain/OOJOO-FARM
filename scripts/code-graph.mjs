#!/usr/bin/env node
// 코드 그래프 생성기 — 실제 소스에서 의존 관계를 읽어 Mermaid 그래프로 docs/code-graph/generated.md 를 만든다.
//
//   node scripts/code-graph.mjs            # 생성 (코드를 바꾼 뒤 다시 실행)
//   node scripts/code-graph.mjs --check    # 생성 결과가 커밋된 파일과 같은지 확인 (다르면 exit 1)
//
// 1) 백엔드 모듈 의존 — ESM import (backend/src)
// 2) 로컬 model-manager 모듈 의존 — ESM import (ml/model-manager), 공유하는 백엔드 모듈 포함
// 3) Android 앱 의존 — Kotlin 파일이 다른 파일의 최상위 선언(class/object/interface/fun)을 참조하는지
//    (같은 패키지는 import 가 없으므로 이름 참조로 본다 — 주석·문자열은 지우고 단어 경계로 찾는다)
// 4) API 지도 — master/farmer ApiService(Retrofit) 엔드포인트 → 처리하는 백엔드 라우트 파일
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'docs/code-graph/generated.md');
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');

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

// ---------- 1, 2) JS import 그래프 ----------

const IMPORT_RE = /(?:^|\n)\s*import\s+(?:[^'";]*?\s+from\s+)?['"]([^'"]+)['"]|import\(\s*[`'"]([^`'"]+)[`'"]\s*\)/g;

function jsImports(file) {
  const src = fs.readFileSync(file, 'utf8');
  const out = [];
  for (const m of src.matchAll(IMPORT_RE)) {
    const spec = m[1] || m[2];
    if (!spec || !spec.startsWith('.')) continue; // 외부 패키지 제외
    const target = path.resolve(path.dirname(file), spec.replace(/\$\{[^}]+\}/g, ''));
    if (fs.existsSync(target) && fs.statSync(target).isFile()) out.push(target);
  }
  return [...new Set(out)];
}

/** 파일 → 그룹(폴더) 이름 */
function groupOf(file, base) {
  const r = path.relative(base, path.dirname(file)).split(path.sep).join('/');
  return r || '.';
}

function jsGraph({ title, base, files, hide = [], hideFrom = [], note = '' }) {
  const all = new Set(files);
  const edges = [];
  for (const f of files) {
    if (hideFrom.some((h) => rel(f).endsWith(h))) continue; // 경로 비교는 OS 무관하게 '/' 기준
    for (const t of jsImports(f)) {
      if (hide.some((h) => rel(t).endsWith(h))) continue;
      edges.push([f, t]);
      all.add(t);
    }
  }
  const groups = new Map();
  for (const f of all) {
    const inside = f.startsWith(base);
    const g = inside ? groupOf(f, base) : `(공유) ${rel(path.dirname(f))}`;
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(f);
  }
  const lines = ['```mermaid', 'flowchart LR'];
  for (const [g, fs_] of [...groups.entries()].sort()) {
    lines.push(`  subgraph ${id(`g_${title}_${g}`)}["${g}"]`);
    for (const f of fs_.sort()) lines.push(`    ${id(rel(f))}["${path.basename(f)}"]`);
    lines.push('  end');
  }
  for (const [a, b] of edges) lines.push(`  ${id(rel(a))} --> ${id(rel(b))}`);
  lines.push('```');
  return { md: [`### ${title}`, '', note, '', ...lines].join('\n'), nodes: all.size, edges: edges.length };
}

// ---------- 3) Kotlin 참조 그래프 ----------

function stripKotlin(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/"""[\s\S]*?"""/g, '""')
    .replace(/"(?:\\.|[^"\\\n])*"/g, '""');
}

const DECL_RE = /^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:internal|public|data|sealed|enum|abstract|open|annotation|value|inline)\s+)*(?:class|object|interface|fun)\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)/;

function kotlinGraph({ title, srcDir, pkgPrefix }) {
  const files = walk(srcDir, ['.kt']);
  const decls = new Map(); // file -> names
  for (const f of files) {
    const names = new Set();
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      if (/^\s/.test(line) || line.startsWith('private ')) continue; // 최상위 공개 선언만
      const m = DECL_RE.exec(line);
      if (m && m[1].length >= 4) names.add(m[1]);
    }
    decls.set(f, names);
  }
  const owner = new Map(); // name -> file (같은 이름이 여러 파일에 있으면 모호 — 제외)
  const dup = new Set();
  for (const [f, names] of decls) for (const n of names) { if (owner.has(n)) dup.add(n); owner.set(n, f); }
  for (const n of dup) owner.delete(n);

  const edges = new Map(); // "a|b" -> 참조 이름 수
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

  // 패키지 단위 요약
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
  for (const p of pkgs) pkgLines.push(`  ${id(`${title}_p_${p}`)}["${pkgPrefix}${p === '(root)' ? '' : `.${p}`} (${pkgCount(p)})"]`);
  for (const [k, n] of [...pkgEdges.entries()].sort()) {
    const [a, b] = k.split('|');
    pkgLines.push(`  ${id(`${title}_p_${a}`)} -- ${n} --> ${id(`${title}_p_${b}`)}`);
  }
  pkgLines.push('```');

  // 파일 단위 — 패키지별 subgraph. 거의 모든 파일이 쓰는 허브(모델·API·문자열·테마)는 따로 표기
  const inDeg = new Map();
  for (const k of edges.keys()) { const t = k.split('|')[1]; inDeg.set(t, (inDeg.get(t) || 0) + 1); }
  const hubs = new Set([...inDeg.entries()].filter(([, n]) => n >= Math.max(6, files.length * 0.3)).map(([f]) => f));
  const fileLines = ['```mermaid', 'flowchart LR'];
  for (const p of pkgs) {
    fileLines.push(`  subgraph ${id(`${title}_s_${p}`)}["${p}"]`);
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
  const hubList = [...hubs].map((f) => `\`${path.basename(f)}\` (${inDeg.get(f)}개 파일이 참조)`).join(', ');
  return {
    md: [
      `### ${title} — 패키지`, '',
      '화살표 숫자 = 그 패키지에서 대상 패키지의 파일을 참조하는 (파일→파일) 관계 수. 괄호 = 파일 수.', '',
      ...pkgLines, '',
      `### ${title} — 파일`, '',
      `★ 허브 파일(대부분의 파일이 참조 — 화살표 생략): ${hubList || '없음'}`, '',
      ...fileLines,
    ].join('\n'),
    files: files.length,
    edges: edges.size,
    shown,
  };
}

// ---------- 4) API 지도 ----------

function backendRoutes() {
  const appSrc = fs.readFileSync(path.join(ROOT, 'backend/src/app.js'), 'utf8');
  const importOf = new Map();
  for (const m of appSrc.matchAll(/import\s+(\w+)(?:,\s*\{[^}]*\})?\s+from\s+'\.\/routes\/([\w-]+)\.js'/g)) importOf.set(m[1], m[2]);
  const mounts = [];
  for (const m of appSrc.matchAll(/app\.use\('([^']+)',\s*(\w+)\)/g)) {
    if (importOf.has(m[2])) mounts.push({ prefix: m[1], file: importOf.get(m[2]) });
  }
  const routes = [];
  // app.js 에 직접 붙은 경로 (예: GET /health)
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
  // 고정 세그먼트가 많이 맞는 라우트 우선 (/plant/:id 가 /:userId 보다)
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
  const table = ['| 앱 | 함수 | 호출 | 백엔드 라우트 파일 |', '|---|---|---|---|'];
  const counts = new Map(); // app|file -> n
  const unmatched = [];
  for (const [app, f] of apps) {
    for (const c of apiCalls(path.join(ROOT, f))) {
      const r = matchRoute(routes, c.method, c.path);
      table.push(`| ${app} | \`${c.fn}\` | \`${c.method} ${c.path}\` | ${r ? `\`${r.file === 'app' ? 'app.js' : `routes/${r.file}.js`}\`` : '**없음**'} |`);
      if (r) counts.set(`${app}|${r.file}`, (counts.get(`${app}|${r.file}`) || 0) + 1);
      else unmatched.push(`${app} ${c.method} ${c.path}`);
    }
  }
  const files = [...new Set([...counts.keys()].map((k) => k.split('|')[1]))].sort();
  const g = ['```mermaid', 'flowchart LR', '  master(["Master 앱"])', '  farmer(["Farmer 앱"])', '  subgraph routes["backend/src/routes"]'];
  for (const f of files.filter((x) => x !== 'app')) g.push(`    r_${id(f)}["${f}.js"]`);
  g.push('  end');
  if (files.includes('app')) g.push('  r_app["app.js (/health)"]');
  for (const [k, n] of [...counts.entries()].sort()) {
    const [app, f] = k.split('|');
    g.push(`  ${app} -- ${n} --> r_${id(f)}`);
  }
  g.push('```');
  return { graph: g.join('\n'), table: table.join('\n'), unmatched, routes: routes.length };
}

// ---------- 생성 ----------

const backend = jsGraph({
  title: '백엔드 (backend/src)',
  base: path.join(ROOT, 'backend/src'),
  files: walk(path.join(ROOT, 'backend/src'), ['.js']),
  hide: ['/db.js'],
  hideFrom: ['/app.js', '/server.js'],
  note: '`db.js`(SQLite 연결·스키마)는 거의 모든 모듈이 import 하고, `app.js` 는 모든 `routes/*.js` 를 `/api/<이름>` 에 마운트한다 — 이 두 가지 화살표는 생략했다 (마운트 경로는 §4 API 지도).',
});
const mm = jsGraph({
  title: '로컬 model-manager (ml/model-manager)',
  base: path.join(ROOT, 'ml/model-manager'),
  files: walk(path.join(ROOT, 'ml/model-manager'), ['.js', '.mjs']).filter((f) => !f.includes(`${path.sep}test${path.sep}`)),
  note: '"(공유)" 그룹은 로컬 도구가 그대로 가져다 쓰는 백엔드 모듈 — 모델 연산(`ojp1.js`)과 내장 모델 정의(`defs.js`).',
});
const master = kotlinGraph({ title: 'Master 앱', srcDir: path.join(ROOT, 'android/master/app/src/main/java/com/oojoo/farm/master'), pkgPrefix: 'master' });
const farmer = kotlinGraph({ title: 'Farmer 앱', srcDir: path.join(ROOT, 'android/slave/app/src/main/java/com/oojoo/farm/slave'), pkgPrefix: 'slave' });
const api = apiMap();

const md = `# 코드 그래프 (자동 생성)

> **이 파일은 \`node scripts/code-graph.mjs\` 가 소스에서 만든다 — 직접 고치지 말 것.**
> 사람이 정리한 흐름·데이터 모델은 [README.md](README.md).

| 그래프 | 규모 |
|---|---|
| 백엔드 모듈 | ${backend.nodes} 모듈, import ${backend.edges}개 (db.js 로 가는 것·app.js 라우터 마운트 제외) |
| 로컬 model-manager 모듈 | ${mm.nodes} 모듈, import ${mm.edges}개 |
| Master 앱 | Kotlin ${master.files} 파일, 파일 간 참조 ${master.edges}개 |
| Farmer 앱 | Kotlin ${farmer.files} 파일, 파일 간 참조 ${farmer.edges}개 |
| API 지도 | 백엔드 라우트 ${api.routes}개, 앱 → 라우트 매칭 실패 ${api.unmatched.length}개 |

## 1. 백엔드

${backend.md}

## 2. 로컬 model-manager

${mm.md}

## 3. Android 앱

Kotlin 은 같은 패키지끼리 import 없이 쓰므로, **다른 파일의 최상위 선언(class / object / interface / fun) 이름을 참조하면** 의존으로 본다 (주석·문자열 제외).

${master.md}

${farmer.md}

## 4. API 지도 — 앱이 부르는 백엔드 라우트

Retrofit \`ApiService\` 의 엔드포인트를 \`backend/src/app.js\` 의 마운트 + 라우트 파일 경로와 맞춘 결과. 화살표 숫자 = 엔드포인트 수.
(Farmer 의 SSE 명령 스트림·모델 파일 다운로드처럼 Retrofit 밖에서 부르는 경로는 포함하지 않는다.)

${api.graph}

${api.unmatched.length ? `**백엔드에서 찾지 못한 호출:** ${api.unmatched.map((u) => `\`${u}\``).join(', ')}\n` : '모든 앱 호출이 백엔드 라우트와 맞는다.\n'}
<details>
<summary>엔드포인트 전체 표</summary>

${api.table}

</details>
`;

if (process.argv.includes('--check')) {
  const cur = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8') : '';
  if (cur !== md) { console.error('docs/code-graph/generated.md is out of date — run: node scripts/code-graph.mjs'); process.exit(1); }
  console.log('code graph up to date');
} else {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, md);
  console.log(`wrote ${rel(OUT)} — backend ${backend.nodes}/${backend.edges}, model-manager ${mm.nodes}/${mm.edges}, master ${master.files}/${master.edges} (shown ${master.shown}), farmer ${farmer.files}/${farmer.edges} (shown ${farmer.shown}), api routes ${api.routes}, unmatched ${api.unmatched.length}`);
  if (api.unmatched.length) console.log('unmatched:', api.unmatched.join(' | '));
}
