// 로컬 모델 레지스트리 — ml/registry/ 아래 파일이 원본이고, 그대로 GitHub 에 올라간다.
//
//  ml/registry/<model>/model.json                 모델 정의: 종류·이름·라벨(ko/en)·종 별칭·기반 모델
//  ml/registry/<model>/samples.json               학습 사진 목록: 라벨·학습/검증 분할·출처·묶음
//  ml/registry/<model>/samples/<sha256>.jpg       학습 사진 (긴 변 256px)
//  ml/registry/<model>/versions/v<N>/version.json 버전: 출력 라벨·sha256·검증 지표·이전 버전 비교·학습 설정·학습에 쓴 사진 목록
//  ml/registry/<model>/versions/v<N>/model.bin    학습한 OJP1 모델 (v1 = 앱 내장 모델은 backend/models 를 가리킨다)
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bundledModelDefs, metricsFromConfusion, normAlias, ID_RE } from '../../../backend/src/ml/defs.js';
import { encodeJpeg, fitWithin } from './image.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
// 프로젝트 루트 (앱 내장 모델·학습 지표 위치) — 레지스트리/커밋 대상 저장소(REPO)는 테스트에서 바꿀 수 있다
const ROOT = path.resolve(HERE, '../../..');
export const REPO = process.env.MM_REPO || ROOT;
export const REGISTRY = process.env.MM_REGISTRY || path.join(REPO, 'ml/registry');
const BUNDLED_DIR = path.join(ROOT, 'backend/models');
const METRICS_DIR = path.join(ROOT, 'ml/plant_health/metrics');
export const SAMPLE_MAX_SIDE = 256;
export { ID_RE, normAlias };

export const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const now = () => new Date().toISOString().slice(0, 19).replace('T', ' ');

function readJson(file, def) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return def; }
}
function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(obj, null, 1)}\n`);
  fs.renameSync(tmp, file);
}

const dirOf = (id) => path.join(REGISTRY, id);
const modelFile = (id) => path.join(dirOf(id), 'model.json');
const samplesFile = (id) => path.join(dirOf(id), 'samples.json');
const versionDir = (id, n) => path.join(dirOf(id), 'versions', `v${n}`);

// ---------- 시드 (앱 내장 16개) ----------

export function seed() {
  for (const d of bundledModelDefs()) {
    if (!fs.existsSync(modelFile(d.id))) writeJson(modelFile(d.id), { ...d, createdAt: now() });
    if (!fs.existsSync(samplesFile(d.id))) writeJson(samplesFile(d.id), []);
    const vf = path.join(versionDir(d.id, 1), 'version.json');
    const bin = path.join(BUNDLED_DIR, `${d.id}.bin`);
    if (fs.existsSync(bin)) {
      const bytes = fs.readFileSync(bin);
      const prev = readJson(vf, null);
      if (!prev || prev.sha256 !== sha256(bytes)) {
        const labels = d.labels.map((l) => l.id);
        const m = readJson(path.join(METRICS_DIR, `${d.id}.json`), null);
        writeJson(vf, {
          version: 1,
          source: 'bundled',
          modelFile: `backend/models/${d.id}.bin`,
          sha256: sha256(bytes),
          size: bytes.length,
          labels,
          parentVersion: null,
          createdAt: prev?.createdAt || now(),
          metrics: m?.confusion ? metricsFromConfusion(m.confusion, labels, { dataset: 'synthetic', nTrain: m.n_train ?? null }) : null,
          baseline: null,
          train: { note: 'app bundled model (synthetic training, ml/plant_health)', maxSampleSeq: 0 },
          dataset: null,
        });
      }
    }
  }
}

// ---------- 모델 ----------

export function listModels() {
  if (!fs.existsSync(REGISTRY)) return [];
  return fs.readdirSync(REGISTRY).filter((n) => fs.existsSync(modelFile(n))).map((n) => readJson(modelFile(n), null)).filter(Boolean)
    .sort((a, b) => (a.kind === b.kind ? (a.createdAt || '').localeCompare(b.createdAt || '') || a.id.localeCompare(b.id) : a.kind.localeCompare(b.kind)));
}

export function getModel(id) {
  return ID_RE.test(id) ? readJson(modelFile(id), null) : null;
}

export function saveModel(m) {
  writeJson(modelFile(m.id), m);
}

export function speciesAliasMap() {
  const map = {};
  for (const m of listModels().filter((x) => x.kind === 'species')) {
    map[normAlias(m.id)] = m.id;
    for (const a of m.aliases || []) map[normAlias(a)] = m.id;
  }
  return map;
}

export function cleanLabels(list) {
  if (!Array.isArray(list)) return { error: 'labels must be an array' };
  const out = [];
  for (const l of list) {
    const id = String(l?.id ?? '').trim();
    if (!ID_RE.test(id)) return { error: `label id '${id}' must be lowercase letters/digits/_ (2-31)` };
    if (out.some((x) => x.id === id)) return { error: `duplicate label ${id}` };
    out.push({ id, ko: String(l.ko || id).trim().slice(0, 40), en: String(l.en || l.ko || id).trim().slice(0, 40) });
  }
  return { labels: out };
}

export function createModel(b) {
  const { id, kind, name } = b;
  if (!ID_RE.test(id || '')) throw httpError(400, 'id must be lowercase letters/digits/_ (2-31), starting with a letter');
  if (!['detector', 'species'].includes(kind)) throw httpError(400, "kind must be 'detector' or 'species'");
  if (getModel(id) || id === 'watering') throw httpError(409, 'model id already exists');
  if (!name) throw httpError(400, 'name required');
  const base = b.baseModel || (kind === 'species' ? 'basil' : 'health_grade');
  const baseModel = getModel(base);
  if (!baseModel || !latestVersion(base)) throw httpError(400, `base model '${base}' has no version`);
  let labels;
  if (b.labels != null && (kind === 'detector' || b.labels.length)) {
    const c = cleanLabels(b.labels);
    if (c.error) throw httpError(400, c.error);
    labels = c.labels;
  } else if (kind === 'species') {
    labels = baseModel.labels;
  }
  if (!labels || labels.length < 2) throw httpError(400, 'at least 2 labels required');
  let aliases = [];
  if (kind === 'species') {
    aliases = [...new Set([name, b.nameEn, ...(b.aliases || [])].filter(Boolean).map((s) => String(s).trim()))];
    const map = speciesAliasMap();
    const clash = aliases.find((a) => map[normAlias(a)] && map[normAlias(a)] !== id);
    if (clash) throw httpError(409, `alias '${clash}' already belongs to ${map[normAlias(clash)]}`);
  }
  const m = {
    id, kind, icon: b.icon || (kind === 'species' ? '🌱' : '🧠'), name: String(name).slice(0, 40),
    nameEn: b.nameEn ? String(b.nameEn).slice(0, 40) : String(name).slice(0, 40),
    description: b.description || '', descriptionEn: b.descriptionEn || '',
    labels, aliases, baseModel: base, createdAt: now(),
  };
  saveModel(m);
  writeJson(samplesFile(id), []);
  return m;
}

export function updateModel(id, b) {
  const m = getModel(id);
  if (!m) throw httpError(404, 'model not found');
  for (const k of ['name', 'nameEn', 'icon', 'description', 'descriptionEn']) if (typeof b[k] === 'string') m[k] = b[k].slice(0, k.startsWith('desc') ? 200 : 40);
  if (m.kind === 'species' && Array.isArray(b.aliases)) {
    const aliases = [...new Set(b.aliases.filter(Boolean).map((s) => String(s).trim()))];
    const map = speciesAliasMap();
    const clash = aliases.find((a) => map[normAlias(a)] && map[normAlias(a)] !== id);
    if (clash) throw httpError(409, `alias '${clash}' already belongs to ${map[normAlias(clash)]}`);
    m.aliases = aliases;
  }
  saveModel(m);
  return m;
}

export function addLabel(id, label) {
  const m = getModel(id);
  if (!m) throw httpError(404, 'model not found');
  const c = cleanLabels([label]);
  if (c.error) throw httpError(400, c.error);
  if (m.labels.some((l) => l.id === c.labels[0].id)) throw httpError(409, 'label already exists');
  m.labels.push(c.labels[0]);
  saveModel(m);
  return m;
}

// ---------- 버전 ----------

export function versions(id) {
  const d = path.join(dirOf(id), 'versions');
  if (!fs.existsSync(d)) return [];
  return fs.readdirSync(d).map((n) => readJson(path.join(d, n, 'version.json'), null)).filter(Boolean).sort((a, b) => b.version - a.version);
}

export function getVersion(id, n) {
  return readJson(path.join(versionDir(id, n), 'version.json'), null);
}

export function latestVersion(id) {
  return versions(id)[0] || null;
}

export function versionModelPath(id, v) {
  return v.source === 'bundled' ? path.join(ROOT, v.modelFile) : path.join(versionDir(id, v.version), 'model.bin');
}

export function saveTrainedVersion(id, data) {
  const n = (latestVersion(id)?.version ?? 1) + 1;
  const bytes = Buffer.from(data.bytes);
  fs.mkdirSync(versionDir(id, n), { recursive: true });
  fs.writeFileSync(path.join(versionDir(id, n), 'model.bin'), bytes);
  const v = {
    version: n,
    source: 'trained',
    modelFile: 'model.bin',
    sha256: sha256(bytes),
    size: bytes.length,
    labels: data.labels,
    parentVersion: data.parent.modelId === id ? data.parent.version : null,
    parentModel: data.parent.modelId,
    createdAt: now(),
    metrics: data.metrics,
    baseline: data.baseline,
    train: data.train,
    dataset: data.dataset,
  };
  writeJson(path.join(versionDir(id, n), 'version.json'), v);
  return v;
}

export function saveEval(id, n, ev) {
  const f = path.join(versionDir(id, n), 'version.json');
  const v = readJson(f, null);
  if (!v) return;
  v.eval = ev;
  writeJson(f, v);
}

// ---------- 학습 사진 ----------

export function samples(id) {
  return readJson(samplesFile(id), []);
}

export function samplePath(id, sha) {
  return path.join(dirOf(id), 'samples', `${sha}.jpg`);
}

/** 라벨마다 묶음의 약 20% 를 검증으로 — 같은 묶음(같은 사진/같은 날 같은 식물/같은 진단 세션)은 같은 쪽 */
function chooseSplit(list, label, group) {
  const same = list.find((s) => s.group === group);
  if (same) return same.split;
  const groups = new Set(); const valGroups = new Set();
  for (const s of list) if (s.label === label) { groups.add(s.group); if (s.split === 'val') valGroups.add(s.group); }
  return valGroups.size < Math.floor((groups.size + 1) * 0.2) ? 'val' : 'train';
}

/**
 * 사진 추가 — 256px 로 줄여 JPEG 로 저장, 내용(sha256)이 같은 사진은 중복 저장하지 않는다.
 * 같은 출처(sourceRef)를 다시 가져오면 라벨만 바꾼다.
 */
export function addSample(id, { img, label, source, sourceRef = null, group, plantId = null, note = null }) {
  const m = getModel(id);
  if (!m) throw httpError(404, 'model not found');
  if (!m.labels.some((l) => l.id === label)) throw httpError(400, `unknown label ${label}`);
  const list = samples(id);
  if (sourceRef) {
    const prev = list.find((s) => s.source === source && s.sourceRef === sourceRef);
    if (prev) {
      prev.label = label;
      writeJson(samplesFile(id), list);
      return { sample: prev, status: 'relabeled' };
    }
  }
  const small = fitWithin(img, SAMPLE_MAX_SIDE);
  const bytes = encodeJpeg(small, 90);
  const sha = sha256(bytes);
  const dup = list.find((s) => s.sha256 === sha);
  if (dup) return { sample: dup, status: 'duplicate' };
  fs.mkdirSync(path.dirname(samplePath(id, sha)), { recursive: true });
  fs.writeFileSync(samplePath(id, sha), bytes);
  const g = group || `photo:${sha.slice(0, 16)}`;
  const s = {
    id: sha.slice(0, 16), sha256: sha, label, split: chooseSplit(list, label, g), source, sourceRef, group: g,
    plantId, note, width: small.width, height: small.height,
    seq: list.reduce((a, x) => Math.max(a, x.seq || 0), 0) + 1, createdAt: now(),
  };
  list.push(s);
  writeJson(samplesFile(id), list);
  return { sample: s, status: 'added' };
}

export function relabelSample(id, sid, label) {
  const m = getModel(id);
  if (!m?.labels.some((l) => l.id === label)) throw httpError(400, 'unknown label');
  const list = samples(id);
  const s = list.find((x) => x.id === sid);
  if (!s) throw httpError(404, 'sample not found');
  s.label = label;
  writeJson(samplesFile(id), list);
  return s;
}

/** 학습 데이터에서 뺀다. 이미 어떤 버전의 학습/검증에 쓰인 사진 파일은 기록 보존을 위해 남긴다. */
export function deleteSample(id, sid) {
  const list = samples(id);
  const s = list.find((x) => x.id === sid);
  if (!s) throw httpError(404, 'sample not found');
  writeJson(samplesFile(id), list.filter((x) => x.id !== sid));
  const used = versions(id).some((v) => (v.dataset || []).some((d) => d.sha256 === s.sha256));
  if (!used) { try { fs.rmSync(samplePath(id, s.sha256)); } catch { /* 이미 없음 */ } }
  return { kept: used };
}

export function sampleCounts(id) {
  const out = {};
  for (const s of samples(id)) {
    out[s.label] ??= { train: 0, val: 0 };
    out[s.label][s.split === 'val' ? 'val' : 'train']++;
  }
  return out;
}

export function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}
