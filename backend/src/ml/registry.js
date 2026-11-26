// model-manager 모델 레지스트리 — 모델 · 버전 · 학습 샘플 · 작업 테이블과 조회 도우미.
//
//  ml_models          모델 1개 = 검출 모듈 모델(kind=detector) 또는 식물 종 모델(kind=species)
//  ml_model_versions  학습/배포 단위. active_version 이 farmer 에 배포되는 버전
//                     (앱 내장 모델은 source=bundled 버전으로 등록 — backend/models/*.bin)
//  ml_samples         라벨이 붙은 학습 사진 (업로드 / farmer 사진 RoI 크롭 / 진단 확정 프레임)
//  ml_jobs            학습·평가 작업 (worker thread 에서 실행)
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import db from '../db.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const BUNDLED_DIR = process.env.DETECTOR_MODELS_DIR || path.resolve(HERE, '../../models');
const METRICS_DIR = path.resolve(HERE, '../../../ml/plant_health/metrics');
export const ML_DATA_DIR = process.env.ML_DATA_DIR || path.resolve('./data/ml');
export const SAMPLE_DIR = path.join(ML_DATA_DIR, 'samples');
export const TRAINED_DIR = path.join(ML_DATA_DIR, 'models');
for (const d of [SAMPLE_DIR, TRAINED_DIR]) fs.mkdirSync(d, { recursive: true });

export const ID_RE = /^[a-z][a-z0-9_]{1,30}$/;

db.exec(`
CREATE TABLE IF NOT EXISTS ml_models (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  name TEXT,
  name_en TEXT,
  icon TEXT,
  description TEXT,
  description_en TEXT,
  labels_json TEXT NOT NULL,
  aliases_json TEXT,
  base_model TEXT,
  active_version INTEGER,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS ml_model_versions (
  id TEXT PRIMARY KEY,
  model_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  source TEXT NOT NULL,
  file TEXT NOT NULL UNIQUE,
  path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  size INTEGER NOT NULL,
  labels_json TEXT NOT NULL,
  parent_version INTEGER,
  train_json TEXT,
  metrics_json TEXT,
  baseline_json TEXT,
  eval_json TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  activated_at TEXT,
  UNIQUE(model_id, version)
);
CREATE TABLE IF NOT EXISTS ml_samples (
  id TEXT PRIMARY KEY,
  model_id TEXT NOT NULL,
  label TEXT NOT NULL,
  file TEXT NOT NULL,
  width INTEGER,
  height INTEGER,
  source TEXT NOT NULL,
  source_ref TEXT,
  group_key TEXT NOT NULL,
  split TEXT NOT NULL,
  plant_id TEXT,
  note TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_ml_samples_model ON ml_samples(model_id, label, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ml_samples_ref ON ml_samples(model_id, source, source_ref) WHERE source_ref IS NOT NULL;
CREATE TABLE IF NOT EXISTS ml_jobs (
  id TEXT PRIMARY KEY,
  model_id TEXT NOT NULL,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  params_json TEXT,
  progress REAL DEFAULT 0,
  message TEXT,
  result_json TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  started_at TEXT,
  finished_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_ml_jobs_model ON ml_jobs(model_id, created_at);
`);

// ---------- 앱 내장 모델 시드 ----------

const SPECIES_LABELS = [
  { id: 'healthy', ko: '건강', en: 'Healthy' },
  { id: 'water_low', ko: '물 부족', en: 'Underwatered' },
  { id: 'water_high', ko: '물 과다', en: 'Overwatered' },
  { id: 'light_low', ko: '햇빛 부족', en: 'Low light' },
  { id: 'pest', ko: '해충 발생', en: 'Pests' },
  { id: 'heat', ko: '온도 더움', en: 'Heat stress' },
];

// farmer PlantHealthNet.ALIAS 와 같은 별칭 (정규화: 소문자·공백 제거)
const SPECIES = {
  basil: ['바질', 'Basil', ['바질', 'basil']],
  cactus: ['선인장', 'Cactus', ['선인장', 'cactus']],
  cherry_tomato: ['방울토마토', 'Cherry tomato', ['방울토마토', '토마토', 'tomato', 'cherrytomato', 'cherry tomato']],
  green_onion: ['대파', 'Green onion', ['대파', '파', 'greenonion', 'green onion']],
  herb: ['허브', 'Herb', ['허브', 'herb', '로즈마리', 'rosemary']],
  lettuce: ['상추', 'Lettuce', ['상추', 'lettuce']],
  onion: ['양파', 'Onion', ['양파', 'onion']],
  pepper: ['고추', 'Pepper', ['고추', 'chili', 'pepper']],
  perilla: ['깻잎', 'Perilla', ['깻잎', 'perilla', 'shiso']],
  potato: ['감자', 'Potato', ['감자', 'potato']],
  pumpkin: ['호박', 'Pumpkin', ['호박', 'pumpkin']],
  strawberry: ['딸기', 'Strawberry', ['딸기', 'strawberry']],
  sweet_potato: ['고구마', 'Sweet potato', ['고구마', 'sweetpotato', 'sweet potato']],
  zucchini: ['애호박', 'Zucchini', ['애호박', 'zucchini', 'squash']],
};

const BUILTIN_DETECTORS = {
  health_grade: {
    icon: '🌿', name: '건강상태 판별', nameEn: 'Health check',
    description: '잎 색·밀도·시듦을 보고 건강등급을 5단계로 판별합니다. 등급이 바뀌면 알림을 보냅니다.',
    descriptionEn: 'Grades plant health in 5 levels from leaf color, density and wilting. Alerts when the grade changes.',
    labels: [
      { id: 'very_healthy', ko: '매우건강', en: 'Very healthy' },
      { id: 'healthy', ko: '건강', en: 'Healthy' },
      { id: 'fair', ko: '보통', en: 'Fair' },
      { id: 'poor', ko: '나쁨', en: 'Poor' },
      { id: 'very_poor', ko: '매우나쁨', en: 'Very poor' },
    ],
  },
  pest: {
    icon: '🐛', name: '해충 검출', nameEn: 'Pest detection',
    description: '식해 구멍·검은 반점으로 해충 발생 정도를 4단계로 판독합니다. 건강등급과 별개 모델입니다.',
    descriptionEn: 'Rates pest damage in 4 levels from bite holes and dark spots. Separate from the health model.',
    labels: [
      { id: 'none', ko: '해충없음', en: 'None' },
      { id: 'light', ko: '경미발생', en: 'Light' },
      { id: 'moderate', ko: '보통발생', en: 'Moderate' },
      { id: 'severe', ko: '심각발생', en: 'Severe' },
    ],
  },
};
export const BUILTIN_DETECTOR_IDS = Object.keys(BUILTIN_DETECTORS);

export const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
export const normAlias = (s) => String(s ?? '').trim().toLowerCase().replace(/ /g, '');
const json = (v) => (v == null ? null : JSON.stringify(v));
const parse = (s, d = null) => { if (s == null) return d; try { return JSON.parse(s); } catch { return d; } };

/** 학습 스크립트가 남긴 합성 데이터 검증 지표 → 버전 metrics 형식 */
function bundledMetrics(id, labels) {
  const f = path.join(METRICS_DIR, `${id}.json`);
  if (!fs.existsSync(f)) return null;
  const m = parse(fs.readFileSync(f, 'utf8'));
  if (!m || !Array.isArray(m.confusion)) return null;
  const confusion = m.confusion;
  const perClass = labels.map((label, k) => {
    const row = confusion[k] || [];
    const support = row.reduce((a, b) => a + b, 0);
    const predicted = confusion.reduce((a, r) => a + (r[k] || 0), 0);
    const tp = row[k] || 0;
    const precision = predicted ? tp / predicted : null;
    const recall = support ? tp / support : null;
    const f1 = precision && recall ? (2 * precision * recall) / (precision + recall) : support ? 0 : null;
    return { label, support, precision, recall, f1 };
  });
  const scored = perClass.filter((c) => c.support > 0);
  const n = perClass.reduce((a, c) => a + c.support, 0);
  return {
    dataset: 'synthetic',
    n,
    accuracy: m.val_acc ?? null,
    macroF1: scored.length ? scored.reduce((a, c) => a + c.f1, 0) / scored.length : null,
    perClass,
    confusion,
    labels,
    nTrain: m.n_train ?? null,
  };
}

function seedModel(id, kind, meta) {
  const file = `${id}.bin`;
  const full = path.join(BUNDLED_DIR, file);
  if (!fs.existsSync(full)) return;
  const bytes = fs.readFileSync(full);
  const hash = sha256(bytes);
  const exists = db.prepare('SELECT id FROM ml_models WHERE id=?').get(id);
  if (!exists) {
    db.prepare(`INSERT INTO ml_models(id, kind, name, name_en, icon, description, description_en, labels_json, aliases_json, base_model)
      VALUES(?,?,?,?,?,?,?,?,?,?)`).run(
      id, kind, meta.name, meta.nameEn, meta.icon ?? null, meta.description ?? null, meta.descriptionEn ?? null,
      json(meta.labels), json(meta.aliases ?? null), null,
    );
  }
  const v = db.prepare("SELECT * FROM ml_model_versions WHERE model_id=? AND source='bundled'").get(id);
  const labelIds = meta.labels.map((l) => l.id);
  if (!v) {
    const n = (db.prepare('SELECT MAX(version) AS v FROM ml_model_versions WHERE model_id=?').get(id).v ?? 0) + 1;
    db.prepare(`INSERT INTO ml_model_versions(id, model_id, version, source, file, path, sha256, size, labels_json, metrics_json, train_json, activated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,datetime('now'))`).run(
      `${id}-v${n}`, id, n, 'bundled', file, full, hash, bytes.length, json(labelIds),
      json(bundledMetrics(id, labelIds)), json({ note: 'app bundled model (synthetic training)' }),
    );
    const m = db.prepare('SELECT active_version FROM ml_models WHERE id=?').get(id);
    if (m.active_version == null) db.prepare('UPDATE ml_models SET active_version=? WHERE id=?').run(n, id);
  } else if (v.sha256 !== hash || v.path !== full) {
    // 재학습 후 scripts/sync-detector-models.mjs 로 내장 모델 파일이 바뀐 경우
    db.prepare('UPDATE ml_model_versions SET sha256=?, size=?, path=? WHERE id=?').run(hash, bytes.length, full, v.id);
  }
}

export function seedBundled() {
  for (const [id, d] of Object.entries(BUILTIN_DETECTORS)) seedModel(id, 'detector', d);
  for (const [id, [ko, en, aliases]] of Object.entries(SPECIES)) {
    seedModel(id, 'species', {
      name: ko, nameEn: en, icon: '🌱',
      description: `${ko} 종별 진단 모델 — 관수 주기 모듈이 물 부족/과다 판독에 쓴다`,
      descriptionEn: `${en} diagnosis model — the watering module reads dry/wet from it`,
      labels: SPECIES_LABELS, aliases,
    });
  }
}
seedBundled();

// ---------- 조회 ----------

export function modelRow(id) {
  return db.prepare('SELECT * FROM ml_models WHERE id=?').get(id) || null;
}

export function labelsMeta(row) {
  return parse(row.labels_json, []);
}

export function versionRows(modelId) {
  return db.prepare('SELECT * FROM ml_model_versions WHERE model_id=? ORDER BY version DESC').all(modelId);
}

export function versionRow(modelId, version) {
  return db.prepare('SELECT * FROM ml_model_versions WHERE model_id=? AND version=?').get(modelId, version) || null;
}

export function activeVersion(modelId) {
  const m = modelRow(modelId);
  if (!m || m.active_version == null) return null;
  return versionRow(modelId, m.active_version);
}

export function allModels() {
  return db.prepare('SELECT * FROM ml_models ORDER BY kind ASC, created_at ASC, id ASC').all();
}

/** 서빙 파일명 → 파일 정보 (어떤 버전이든 — 배포 직후 이전 버전을 받는 중인 farmer 도 있다). */
export function fileByName(file) {
  if (!/^[a-z0-9_-]+\.bin$/.test(file)) return null;
  const v = db.prepare('SELECT * FROM ml_model_versions WHERE file=?').get(file);
  if (!v || !fs.existsSync(v.path)) return null;
  return { file: v.file, path: v.path, sha256: v.sha256, size: v.size };
}

export function versionRef(v) {
  return {
    file: v.file,
    url: `/api/detectors/models/${v.file}`,
    sha256: v.sha256,
    size: v.size,
    labels: parse(v.labels_json, []),
    version: v.version,
  };
}

/** 종 별칭(정규화) → 종 모델 id — 기본은 배포 버전이 있는 종만 (all: 아직 학습 전인 새 종 포함). */
export function speciesAliasMap({ all = false } = {}) {
  const map = {};
  const sql = all ? "SELECT * FROM ml_models WHERE kind='species'" : "SELECT * FROM ml_models WHERE kind='species' AND active_version IS NOT NULL";
  for (const m of db.prepare(sql).all()) {
    map[normAlias(m.id)] = m.id;
    for (const a of parse(m.aliases_json, [])) map[normAlias(a)] = m.id;
  }
  return map;
}

export function resolveSpecies(raw, map = speciesAliasMap()) {
  const k = normAlias(raw);
  return k ? map[k] ?? null : null;
}

export function parseJson(s, d = null) {
  return parse(s, d);
}
