// model-manager API (/api/models) — 모델별 라벨·학습 사진 관리, 학습/평가 작업, 버전 배포, 성능 조회.
// 화면: GET /model-manager (src/model-manager.html)
// 환경변수 MODEL_MANAGER_TOKEN 이 있으면 모든 요청에 x-admin-token 헤더(또는 ?token=)가 필요하다.
import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import {
  ID_RE, SAMPLE_DIR, modelRow, labelsMeta, versionRows, versionRow, activeVersion, parseJson,
  speciesAliasMap, resolveSpecies, normAlias,
} from '../ml/registry.js';
import { decodeImage, encodeJpeg, fitWithin, cropNormalized } from '../ml/image.js';
import { enqueueJob, jobRow, recentJobs, activeJobs } from '../ml/jobs.js';
import {
  fieldStats, humanAgreement, sampleCounts, usage, health, allSummaries, unmatchedSpecies,
} from '../ml/stats.js';
import { queueDetectorSync } from './detectors.js';

const r = Router();
const PHOTO_DIR = path.resolve('./data/photos');
const DIAGNOSIS_DIR = path.resolve('./data/diagnosis');
const SAMPLE_MAX_SIDE = 256;

const upload = multer({ storage: multer.memoryStorage(), limits: { files: 40, fileSize: 15 * 1024 * 1024 } });

r.use((req, res, next) => {
  const token = process.env.MODEL_MANAGER_TOKEN;
  if (!token) return next();
  if ((req.get('x-admin-token') || req.query.token) === token) return next();
  res.status(401).json({ error: 'model-manager token required' });
});

const err = (res, code, msg) => res.status(code).json({ error: msg });

function loadModel(req, res) {
  const m = modelRow(req.params.id);
  if (!m) err(res, 404, 'model not found');
  return m;
}

const hasBundled = (id) => !!db.prepare("SELECT 1 FROM ml_model_versions WHERE model_id=? AND source='bundled'").get(id);

function toVersion(v, activeV) {
  return {
    version: v.version,
    source: v.source,
    file: v.file,
    sha256: v.sha256,
    size: v.size,
    labels: parseJson(v.labels_json, []),
    parentVersion: v.parent_version,
    createdAt: v.created_at,
    activatedAt: v.activated_at,
    active: v.version === activeV,
    metrics: parseJson(v.metrics_json, null),
    baseline: parseJson(v.baseline_json, null),
    train: parseJson(v.train_json, null),
    eval: parseJson(v.eval_json, null),
  };
}

function toModel(m) {
  return {
    id: m.id,
    kind: m.kind,
    icon: m.icon,
    name: m.name,
    nameEn: m.name_en,
    description: m.description,
    descriptionEn: m.description_en,
    labels: labelsMeta(m),
    aliases: parseJson(m.aliases_json, []),
    baseModel: m.base_model,
    activeVersion: m.active_version,
    createdAt: m.created_at,
    builtin: hasBundled(m.id),
  };
}

function cleanLabels(list) {
  if (!Array.isArray(list)) return { error: 'labels must be an array' };
  const out = [];
  for (const l of list) {
    const id = String(l?.id ?? '').trim();
    if (!ID_RE.test(id)) return { error: `label id '${id}' must match ${ID_RE}` };
    if (out.some((x) => x.id === id)) return { error: `duplicate label ${id}` };
    out.push({ id, ko: String(l.ko || id).slice(0, 40), en: String(l.en || l.ko || id).slice(0, 40) });
  }
  return { labels: out };
}

/** 검증 분할 — 같은 그룹(같은 사진/세션/같은 날 같은 식물)은 같은 쪽. 라벨별로 그룹의 약 20% 를 검증으로. */
function chooseSplit(modelId, label, groupKey) {
  const same = db.prepare('SELECT split FROM ml_samples WHERE model_id=? AND group_key=? LIMIT 1').get(modelId, groupKey);
  if (same) return same.split;
  const g = db.prepare(`SELECT COUNT(DISTINCT group_key) AS n,
      COUNT(DISTINCT CASE WHEN split='val' THEN group_key END) AS v
    FROM ml_samples WHERE model_id=? AND label=?`).get(modelId, label);
  return g.v < Math.floor((g.n + 1) * 0.2) ? 'val' : 'train';
}

function addSample(model, { label, img, source, sourceRef = null, groupKey, plantId = null, note = null }) {
  const small = fitWithin(img, SAMPLE_MAX_SIDE);
  const id = nanoid(12);
  const file = `${id}.jpg`;
  const dir = path.join(SAMPLE_DIR, model.id);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, file), encodeJpeg(small, 90));
  const split = chooseSplit(model.id, label, groupKey);
  db.prepare(`INSERT INTO ml_samples(id, model_id, label, file, width, height, source, source_ref, group_key, split, plant_id, note)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).run(id, model.id, label, file, small.width, small.height, source, sourceRef, groupKey, split, plantId, note);
  return { id, split };
}

function toSample(s, token) {
  const q = token ? `?token=${encodeURIComponent(token)}` : '';
  return {
    id: s.id,
    label: s.label,
    split: s.split,
    source: s.source,
    sourceRef: s.source_ref,
    plantId: s.plant_id,
    note: s.note,
    width: s.width,
    height: s.height,
    createdAt: s.created_at,
    url: `/api/models/${s.model_id}/samples/${s.id}/image${q}`,
  };
}

// ---------- 목록 / 생성 ----------

r.get('/', (req, res) => {
  res.json({ models: allSummaries(), unmatchedSpecies: unmatchedSpecies(), jobs: activeJobs() });
});

r.get('/jobs/:jobId', (req, res) => {
  const j = jobRow(req.params.jobId);
  if (!j) return err(res, 404, 'job not found');
  res.json(j);
});

r.post('/', (req, res) => {
  const { id, kind, name, nameEn, icon, description, descriptionEn, aliases = [], baseModel } = req.body || {};
  if (!ID_RE.test(id || '')) return err(res, 400, `id must match ${ID_RE}`);
  if (!['detector', 'species'].includes(kind)) return err(res, 400, "kind must be 'detector' or 'species'");
  if (modelRow(id) || ['watering'].includes(id)) return err(res, 409, 'model id already exists');
  if (!name) return err(res, 400, 'name required');
  const base = baseModel || (kind === 'species' ? 'basil' : 'health_grade');
  const baseRow = modelRow(base);
  if (!baseRow || !activeVersion(base)) return err(res, 400, `base model '${base}' has no deployed version`);
  let labels;
  if (req.body.labels != null) {
    const c = cleanLabels(req.body.labels);
    if (c.error) return err(res, 400, c.error);
    labels = c.labels;
  } else if (kind === 'species') {
    labels = labelsMeta(baseRow); // 종 모델은 기반 종과 같은 진단 라벨로 시작
  }
  if (!labels || labels.length < 2) return err(res, 400, 'at least 2 labels required');
  let aliasList = [];
  if (kind === 'species') {
    aliasList = [...new Set([name, nameEn, ...(Array.isArray(aliases) ? aliases : [])].filter(Boolean).map(String))];
    const map = speciesAliasMap({ all: true });
    const clash = aliasList.find((a) => map[normAlias(a)] && map[normAlias(a)] !== id);
    if (clash) return err(res, 409, `alias '${clash}' already belongs to ${map[normAlias(clash)]}`);
  }
  db.prepare(`INSERT INTO ml_models(id, kind, name, name_en, icon, description, description_en, labels_json, aliases_json, base_model)
    VALUES(?,?,?,?,?,?,?,?,?,?)`).run(
    id, kind, String(name).slice(0, 40), nameEn ? String(nameEn).slice(0, 40) : null, icon || (kind === 'species' ? '🌱' : '🧠'),
    description || null, descriptionEn || null, JSON.stringify(labels), kind === 'species' ? JSON.stringify(aliasList) : null, base,
  );
  res.json({ model: toModel(modelRow(id)) });
});

// ---------- 모델 상세 / 수정 / 삭제 ----------

r.get('/:id', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const field = fieldStats(m);
  const agreement = humanAgreement(m);
  res.json({
    model: toModel(m),
    versions: versionRows(m.id).map((v) => toVersion(v, m.active_version)),
    samples: sampleCounts(m.id),
    field,
    agreement,
    health: health(m, field, agreement),
    usage: usage(m),
    jobs: recentJobs(m.id),
  });
});

r.put('/:id', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const b = req.body || {};
  let aliases = parseJson(m.aliases_json, null);
  if (m.kind === 'species' && Array.isArray(b.aliases)) {
    aliases = [...new Set(b.aliases.filter(Boolean).map(String))];
    const map = speciesAliasMap({ all: true });
    const clash = aliases.find((a) => map[normAlias(a)] && map[normAlias(a)] !== m.id);
    if (clash) return err(res, 409, `alias '${clash}' already belongs to ${map[normAlias(clash)]}`);
  }
  db.prepare('UPDATE ml_models SET name=?, name_en=?, icon=?, description=?, description_en=?, aliases_json=? WHERE id=?').run(
    b.name ?? m.name, b.nameEn ?? m.name_en, b.icon ?? m.icon, b.description ?? m.description,
    b.descriptionEn ?? m.description_en, aliases ? JSON.stringify(aliases) : null, m.id,
  );
  res.json({ model: toModel(modelRow(m.id)) });
});

r.delete('/:id', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  if (hasBundled(m.id)) return err(res, 400, 'app-bundled models cannot be deleted');
  if (activeJobs().some((j) => j.modelId === m.id)) return err(res, 409, 'a job is running for this model');
  for (const v of versionRows(m.id)) { try { fs.rmSync(v.path); } catch { /* 이미 없음 */ } }
  try { fs.rmSync(path.join(SAMPLE_DIR, m.id), { recursive: true, force: true }); } catch { /* noop */ }
  db.prepare('DELETE FROM ml_model_versions WHERE model_id=?').run(m.id);
  db.prepare('DELETE FROM ml_samples WHERE model_id=?').run(m.id);
  db.prepare('DELETE FROM ml_jobs WHERE model_id=?').run(m.id);
  db.prepare('DELETE FROM ml_models WHERE id=?').run(m.id);
  res.json({ ok: true });
});

r.post('/:id/labels', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const c = cleanLabels([req.body || {}]);
  if (c.error) return err(res, 400, c.error);
  const labels = labelsMeta(m);
  if (labels.some((l) => l.id === c.labels[0].id)) return err(res, 409, 'label already exists');
  labels.push(c.labels[0]);
  db.prepare('UPDATE ml_models SET labels_json=? WHERE id=?').run(JSON.stringify(labels), m.id);
  res.json({ model: toModel(modelRow(m.id)) });
});

// ---------- 학습 사진 ----------

r.get('/:id/samples', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 60, 1), 200);
  const offset = Math.max(Number.parseInt(req.query.offset, 10) || 0, 0);
  const label = typeof req.query.label === 'string' && req.query.label ? req.query.label : null;
  const where = label ? 'model_id=? AND label=?' : 'model_id=?';
  const args = label ? [m.id, label] : [m.id];
  const rows = db.prepare(`SELECT * FROM ml_samples WHERE ${where} ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?`).all(...args, limit, offset);
  const total = db.prepare(`SELECT COUNT(*) AS n FROM ml_samples WHERE ${where}`).get(...args).n;
  res.json({ total, samples: rows.map((s) => toSample(s, process.env.MODEL_MANAGER_TOKEN ? req.get('x-admin-token') || req.query.token : null)) });
});

r.get('/:id/samples/:sid/image', (req, res) => {
  const s = db.prepare('SELECT * FROM ml_samples WHERE id=? AND model_id=?').get(req.params.sid, req.params.id);
  if (!s) return err(res, 404, 'sample not found');
  const f = path.join(SAMPLE_DIR, s.model_id, s.file);
  if (!fs.existsSync(f)) return err(res, 404, 'file missing');
  res.type('image/jpeg').send(fs.readFileSync(f));
});

// 사진 직접 추가 (인위적 학습 데이터) — multipart: photos(반복), label, note?, plantId?
r.post('/:id/samples', upload.array('photos', 40), (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const { label, note, plantId } = req.body || {};
  if (!labelsMeta(m).some((l) => l.id === label)) return err(res, 400, 'unknown label');
  const files = req.files || [];
  if (!files.length) return err(res, 400, 'photos required');
  const accepted = [];
  const rejected = [];
  for (const f of files) {
    try {
      const img = decodeImage(f.buffer);
      if (img.width < 16 || img.height < 16) throw new Error('too small (min 16px)');
      const id = nanoid(8);
      accepted.push(addSample(m, { label, img, source: 'upload', groupKey: `upload:${id}`, plantId: plantId || null, note: note ? String(note).slice(0, 200) : null }));
    } catch (e) {
      rejected.push({ name: f.originalname, error: e.message });
    }
  }
  res.json({ accepted: accepted.length, rejected, samples: accepted });
});

r.put('/:id/samples/:sid', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const { label } = req.body || {};
  if (!labelsMeta(m).some((l) => l.id === label)) return err(res, 400, 'unknown label');
  const info = db.prepare('UPDATE ml_samples SET label=? WHERE id=? AND model_id=?').run(label, req.params.sid, m.id);
  if (!info.changes) return err(res, 404, 'sample not found');
  res.json({ ok: true });
});

r.delete('/:id/samples/:sid', (req, res) => {
  const s = db.prepare('SELECT * FROM ml_samples WHERE id=? AND model_id=?').get(req.params.sid, req.params.id);
  if (!s) return err(res, 404, 'sample not found');
  try { fs.rmSync(path.join(SAMPLE_DIR, s.model_id, s.file)); } catch { /* noop */ }
  db.prepare('DELETE FROM ml_samples WHERE id=?').run(s.id);
  res.json({ ok: true });
});

// ---------- farmer 사진 / 진단 프레임 가져오기 ----------

/** 이 모델 학습에 쓸 수 있는 식물(종 모델이면 그 종만)과, plantId 를 주면 그 식물의 farmer 사진 */
r.get('/:id/candidates', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const aliases = speciesAliasMap({ all: true });
  const plants = db.prepare(`SELECT p.id, p.name, p.species, p.roi_json, COUNT(ph.id) AS photos
      FROM plants p LEFT JOIN plant_photos ph ON ph.plant_id = p.id GROUP BY p.id ORDER BY photos DESC, p.name`).all()
    .filter((p) => m.kind !== 'species' || resolveSpecies(p.species ?? p.name, aliases) === m.id)
    .map((p) => ({ id: p.id, name: p.name, species: p.species, roi: parseJson(p.roi_json, null), photos: p.photos }));
  let photos = [];
  if (req.query.plantId) {
    const imported = new Map(db.prepare("SELECT source_ref, label FROM ml_samples WHERE model_id=? AND source='farmer_photo'").all(m.id)
      .map((s) => [s.source_ref, s.label]));
    photos = db.prepare('SELECT * FROM plant_photos WHERE plant_id=? ORDER BY COALESCE(taken_at, created_at) DESC LIMIT 120').all(req.query.plantId)
      .map((ph) => ({
        id: ph.id,
        takenAt: ph.taken_at || ph.created_at,
        url: `/api/photos/file/${ph.filename}`,
        thumbUrl: ph.thumb_filename ? `/api/photos/file/${ph.thumb_filename}` : `/api/photos/file/${ph.filename}`,
        importedAs: imported.get(ph.id) ?? null,
      }));
  }
  res.json({ plants, photos });
});

// body: { photos: [{photoId, label}] } — 식물 RoI 로 잘라 저장 (RoI 없으면 사진 전체). 이미 가져온 사진은 라벨만 갱신
r.post('/:id/samples/import', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const items = req.body?.photos;
  if (!Array.isArray(items) || !items.length || items.length > 100) return err(res, 400, 'photos[1..100] required');
  const labels = labelsMeta(m).map((l) => l.id);
  let imported = 0; let relabeled = 0;
  const failed = [];
  for (const it of items) {
    if (!labels.includes(it?.label)) { failed.push({ photoId: it?.photoId, error: 'unknown label' }); continue; }
    const ph = db.prepare('SELECT * FROM plant_photos WHERE id=?').get(it.photoId);
    if (!ph) { failed.push({ photoId: it.photoId, error: 'photo not found' }); continue; }
    const prev = db.prepare("SELECT id FROM ml_samples WHERE model_id=? AND source='farmer_photo' AND source_ref=?").get(m.id, ph.id);
    if (prev) {
      db.prepare('UPDATE ml_samples SET label=? WHERE id=?').run(it.label, prev.id);
      relabeled++;
      continue;
    }
    try {
      let img = decodeImage(fs.readFileSync(path.join(PHOTO_DIR, ph.filename)));
      const plant = db.prepare('SELECT roi_json FROM plants WHERE id=?').get(ph.plant_id);
      const roi = parseJson(plant?.roi_json, null);
      if (roi) img = cropNormalized(img, roi);
      const day = String(ph.taken_at || ph.created_at || '').slice(0, 10);
      addSample(m, {
        label: it.label, img, source: 'farmer_photo', sourceRef: ph.id,
        groupKey: `plant:${ph.plant_id}:${day}`, plantId: ph.plant_id, note: roi ? 'roi' : 'full frame',
      });
      imported++;
    } catch (e) {
      failed.push({ photoId: it.photoId, error: e.message });
    }
  }
  res.json({ imported, relabeled, failed });
});

// 영상 건강진단에서 사람이 확정한 라벨의 프레임 (종 모델) — 이미 가져온 프레임은 건너뜀
r.post('/:id/samples/import-diagnosis', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  if (m.kind !== 'species') return err(res, 400, 'diagnosis frames are for species models');
  const labels = labelsMeta(m).map((l) => l.id);
  let sessions = [];
  try {
    sessions = db.prepare('SELECT * FROM diagnosis_sessions WHERE human_label IS NOT NULL').all();
  } catch { sessions = []; }
  const aliases = speciesAliasMap({ all: true });
  let imported = 0; let skipped = 0;
  for (const s of sessions) {
    const plant = db.prepare('SELECT name, species FROM plants WHERE id=?').get(s.plant_id);
    const target = s.model_id || resolveSpecies(plant?.species ?? plant?.name, aliases);
    if (target !== m.id || !labels.includes(s.human_label)) continue;
    const frames = db.prepare('SELECT * FROM diagnosis_frames WHERE session_id=? ORDER BY frame_index').all(s.id);
    for (const f of frames) {
      const ref = `${s.id}:${f.frame_index}`;
      if (db.prepare("SELECT 1 FROM ml_samples WHERE model_id=? AND source='diagnosis' AND source_ref=?").get(m.id, ref)) { skipped++; continue; }
      try {
        const img = decodeImage(fs.readFileSync(path.join(DIAGNOSIS_DIR, s.id, f.filename)));
        addSample(m, { label: s.human_label, img, source: 'diagnosis', sourceRef: ref, groupKey: `diag:${s.id}`, plantId: s.plant_id });
        imported++;
      } catch { skipped++; }
    }
  }
  res.json({ imported, skipped });
});

// ---------- 학습 / 평가 / 배포 ----------

r.post('/:id/train', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  if (activeJobs().some((j) => j.modelId === m.id)) return err(res, 409, 'a job is already queued or running for this model');
  const b = req.body || {};
  const epochs = b.epochs == null ? 15 : Number(b.epochs);
  const lr = b.lr == null ? 0.01 : Number(b.lr);
  const from = b.from === 'base' ? 'base' : 'active';
  if (!Number.isInteger(epochs) || epochs < 1 || epochs > 60) return err(res, 400, 'epochs must be an integer 1..60');
  if (!(lr >= 0.0005 && lr <= 0.1)) return err(res, 400, 'lr must be in 0.0005..0.1');
  const active = activeVersion(m.id);
  const parent = from === 'base' || !active ? (m.base_model ? activeVersion(m.base_model) : null) || active : active;
  if (!parent) return err(res, 400, 'no deployed version or base model to start from');
  const counts = sampleCounts(m.id);
  const labels = labelsMeta(m).map((l) => l.id);
  const totalTrain = labels.reduce((a, l) => a + (counts[l]?.train ?? 0), 0);
  if (totalTrain < 5) return err(res, 400, `need at least 5 training photos (have ${totalTrain})`);
  // 시작 모델이 모르는 라벨은 사진이 있어야 배울 수 있다
  const parentLabels = parseJson(parent.labels_json, []);
  const missing = labels.filter((l) => !parentLabels.includes(l) && (counts[l]?.train ?? 0) < 3);
  if (missing.length) return err(res, 400, `new labels need at least 3 training photos each: ${missing.join(', ')}`);
  const jobId = enqueueJob('train', m.id, { epochs, lr, from });
  res.json({ jobId });
});

r.post('/:id/evaluate', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const version = req.body?.version != null ? Number(req.body.version) : m.active_version;
  if (version == null || !versionRow(m.id, version)) return err(res, 400, 'no version to evaluate');
  const n = db.prepare('SELECT COUNT(*) AS n FROM ml_samples WHERE model_id=?').get(m.id).n;
  if (!n) return err(res, 400, 'no photos to evaluate on');
  if (activeJobs().some((j) => j.modelId === m.id)) return err(res, 409, 'a job is already queued or running for this model');
  res.json({ jobId: enqueueJob('evaluate', m.id, { version }) });
});

r.post('/:id/versions/:version/activate', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const v = versionRow(m.id, Number(req.params.version));
  if (!v) return err(res, 404, 'version not found');
  if (!fs.existsSync(v.path)) return err(res, 409, 'model file missing');
  db.prepare('UPDATE ml_models SET active_version=? WHERE id=?').run(v.version, m.id);
  db.prepare("UPDATE ml_model_versions SET activated_at=datetime('now') WHERE id=?").run(v.id);
  // farmer 들이 5분 주기를 기다리지 않고 새 모델을 받도록
  const slaves = db.prepare('SELECT DISTINCT slave_id FROM plants WHERE slave_id IS NOT NULL').all().map((x) => x.slave_id);
  for (const s of slaves) queueDetectorSync({ id: null, slave_id: s });
  res.json({ ok: true, modelId: m.id, version: v.version, sha256: v.sha256, notified: slaves.length });
});

r.get('/:id/versions/:version/download', (req, res) => {
  const v = versionRow(req.params.id, Number(req.params.version));
  if (!v || !fs.existsSync(v.path)) return err(res, 404, 'version not found');
  res.set('Content-Disposition', `attachment; filename="${v.file}"`);
  res.type('application/octet-stream').send(fs.readFileSync(v.path));
});

export default r;
