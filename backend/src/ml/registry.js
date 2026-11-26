// 서버 모델 배포 레지스트리 — 로컬 model-manager(ml/model-manager)가 올린 모델 버전을 보관하고,
// 배포(active) 버전으로 farmer 검출 카탈로그(/api/detectors)를 결정한다.
//
//  ml_models          모델 1개 = 검출 모듈 모델(kind=detector) 또는 식물 종 모델(kind=species)
//  ml_model_versions  버전. source=bundled(앱 내장, backend/models) | uploaded(로컬에서 학습해 업로드)
//                     dataset_json = 그 버전을 학습·검증한 사진 목록(sha256·라벨·분할) — 사진은 DATASET_DIR 에 내용 주소로
// 학습·라벨링은 서버에서 하지 않는다.
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import db from '../db.js';
import { bundledModelDefs, metricsFromConfusion, normAlias, BUILTIN_DETECTOR_IDS } from './defs.js';

export { BUILTIN_DETECTOR_IDS, normAlias };

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const BUNDLED_DIR = process.env.DETECTOR_MODELS_DIR || path.resolve(HERE, '../../models');
const METRICS_DIR = path.resolve(HERE, '../../../ml/plant_health/metrics');
export const ML_DATA_DIR = process.env.ML_DATA_DIR || path.resolve('./data/ml');
export const UPLOADED_DIR = path.join(ML_DATA_DIR, 'models');
export const DATASET_DIR = path.join(ML_DATA_DIR, 'datasets');
for (const d of [UPLOADED_DIR, DATASET_DIR]) fs.mkdirSync(d, { recursive: true });

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
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT
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
  dataset_json TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  uploaded_at TEXT,
  activated_at TEXT,
  UNIQUE(model_id, version)
);
`);

export const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const json = (v) => (v == null ? null : JSON.stringify(v));
export function parseJson(s, d = null) { if (s == null) return d; try { return JSON.parse(s); } catch { return d; } }

function bundledMetrics(id, labelIds) {
  const f = path.join(METRICS_DIR, `${id}.json`);
  if (!fs.existsSync(f)) return null;
  const m = parseJson(fs.readFileSync(f, 'utf8'));
  if (!m || !Array.isArray(m.confusion)) return null;
  return metricsFromConfusion(m.confusion, labelIds, { dataset: 'synthetic', nTrain: m.n_train ?? null });
}

/** 앱 내장 모델 16개를 v1(source=bundled)로 등록 — 처음 한 번, 파일이 바뀌면 sha 갱신 */
export function seedBundled() {
  for (const d of bundledModelDefs()) {
    const file = `${d.id}.bin`;
    const full = path.join(BUNDLED_DIR, file);
    if (!fs.existsSync(full)) continue;
    const bytes = fs.readFileSync(full);
    const hash = sha256(bytes);
    if (!db.prepare('SELECT id FROM ml_models WHERE id=?').get(d.id)) {
      db.prepare(`INSERT INTO ml_models(id, kind, name, name_en, icon, description, description_en, labels_json, aliases_json, base_model)
        VALUES(?,?,?,?,?,?,?,?,?,?)`).run(d.id, d.kind, d.name, d.nameEn, d.icon, d.description, d.descriptionEn,
        json(d.labels), json(d.aliases), null);
    }
    const v = db.prepare("SELECT * FROM ml_model_versions WHERE model_id=? AND source='bundled'").get(d.id);
    const labelIds = d.labels.map((l) => l.id);
    if (!v) {
      db.prepare(`INSERT INTO ml_model_versions(id, model_id, version, source, file, path, sha256, size, labels_json, metrics_json, train_json, activated_at)
        VALUES(?,?,1,'bundled',?,?,?,?,?,?,?,datetime('now'))`).run(
        `${d.id}-v1`, d.id, file, full, hash, bytes.length, json(labelIds), json(bundledMetrics(d.id, labelIds)),
        json({ note: 'app bundled model (synthetic training)' }),
      );
      const m = db.prepare('SELECT active_version FROM ml_models WHERE id=?').get(d.id);
      if (m.active_version == null) db.prepare('UPDATE ml_models SET active_version=1 WHERE id=?').run(d.id);
    } else if (v.sha256 !== hash || v.path !== full) {
      db.prepare('UPDATE ml_model_versions SET sha256=?, size=?, path=? WHERE id=?').run(hash, bytes.length, full, v.id);
    }
  }
}
seedBundled();

// ---------- 조회 ----------

export function modelRow(id) {
  return db.prepare('SELECT * FROM ml_models WHERE id=?').get(id) || null;
}

export function labelsMeta(row) {
  return parseJson(row.labels_json, []);
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

/** 서빙 파일명 → 파일 정보 (어떤 버전이든 — 배포 직후 이전 버전을 받는 중인 farmer 도 있다) */
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
    labels: parseJson(v.labels_json, []),
    version: v.version,
  };
}

/** 종 별칭(정규화) → 종 모델 id — 기본은 배포 버전이 있는 종만 (all: 아직 배포 전인 종 포함) */
export function speciesAliasMap({ all = false } = {}) {
  const map = {};
  const sql = all ? "SELECT * FROM ml_models WHERE kind='species'" : "SELECT * FROM ml_models WHERE kind='species' AND active_version IS NOT NULL";
  for (const m of db.prepare(sql).all()) {
    map[normAlias(m.id)] = m.id;
    for (const a of parseJson(m.aliases_json, [])) map[normAlias(a)] = m.id;
  }
  return map;
}

export function resolveSpecies(raw, map = speciesAliasMap()) {
  const k = normAlias(raw);
  return k ? map[k] ?? null : null;
}

export function datasetFile(modelId, sha) {
  return path.join(DATASET_DIR, modelId, `${sha}.jpg`);
}
