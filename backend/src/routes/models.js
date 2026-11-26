// 서버 모델 API (/api/models) — 로컬 model-manager(ml/model-manager)가 쓰는 업로드·배포·조회 창구.
// 학습·라벨링은 로컬에서 하고, 서버는 결과(모델 + 라벨 + 학습/검증 사진 + 지표)를 받아 보관하고 farmer 에 배포한다.
//
//  GET  /                         모델 목록 (배포 버전·현장 성능 요약) + 모델 없는 식물 종
//  GET  /:id                      모델 상세 (버전·현장 성능·사람 확인 일치율·재학습 신호·사용 식물)
//  GET  /:id/candidates           학습 사진 후보 — 식물(RoI 포함)과 farmer 사진 (?plantId=)
//  GET  /:id/diagnosis-frames     영상 진단에서 사람이 확정한 라벨의 프레임 (종 모델)
//  POST /:id/datasets/missing     서버에 아직 없는 학습 사진 sha256 목록            [토큰]
//  POST /:id/releases             모델 버전 업로드 (multipart: manifest, model, samples)  [토큰]
//  POST /:id/versions/:v/activate 배포 / 롤백 → farmer 재동기화                     [토큰]
//  GET  /:id/versions/:v/download 모델 파일
//  GET  /:id/versions/:v/dataset  그 버전의 학습·검증 사진 목록
// 쓰기는 서버 환경변수 MODEL_MANAGER_TOKEN 과 같은 x-admin-token 이 있어야 한다 (설정이 없으면 쓰기 불가).
import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import db from '../db.js';
import {
  modelRow, labelsMeta, versionRows, versionRow, parseJson, speciesAliasMap, resolveSpecies, normAlias,
  UPLOADED_DIR, datasetFile, sha256, BUILTIN_DETECTOR_IDS,
} from '../ml/registry.js';
import { ID_RE } from '../ml/defs.js';
import { parseModel } from '../ml/ojp1.js';
import { fieldStats, humanAgreement, usage, health, allSummaries, unmatchedSpecies } from '../ml/stats.js';
import { queueDetectorSync } from './detectors.js';

const r = Router();
const upload = multer({ storage: multer.memoryStorage(), limits: { files: 3000, fileSize: 4 * 1024 * 1024, fieldSize: 8 * 1024 * 1024 } });
const err = (res, code, msg) => res.status(code).json({ error: msg });
const SHA_RE = /^[0-9a-f]{64}$/;

function requireToken(req, res, next) {
  const token = process.env.MODEL_MANAGER_TOKEN;
  if (!token) return err(res, 403, 'MODEL_MANAGER_TOKEN is not set on the server — uploads are disabled');
  if (req.get('x-admin-token') !== token) return err(res, 401, 'model-manager token required');
  next();
}

function loadModel(req, res) {
  const m = modelRow(req.params.id);
  if (!m) err(res, 404, 'model not found');
  return m;
}

function toVersion(v, activeV) {
  const dataset = parseJson(v.dataset_json, null);
  return {
    version: v.version,
    source: v.source,
    file: v.file,
    sha256: v.sha256,
    size: v.size,
    labels: parseJson(v.labels_json, []),
    parentVersion: v.parent_version,
    createdAt: v.created_at,
    uploadedAt: v.uploaded_at,
    activatedAt: v.activated_at,
    active: v.version === activeV,
    metrics: parseJson(v.metrics_json, null),
    baseline: parseJson(v.baseline_json, null),
    train: parseJson(v.train_json, null),
    datasetSize: dataset ? dataset.length : null,
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
    builtin: !!db.prepare("SELECT 1 FROM ml_model_versions WHERE model_id=? AND source='bundled'").get(m.id),
  };
}

// ---------- 조회 ----------

r.get('/', (req, res) => {
  res.json({ models: allSummaries(), unmatchedSpecies: unmatchedSpecies() });
});

r.get('/:id', (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const field = fieldStats(m);
  const agreement = humanAgreement(m);
  res.json({
    model: toModel(m),
    versions: versionRows(m.id).map((v) => toVersion(v, m.active_version)),
    field,
    agreement,
    health: health(m, field, agreement),
    usage: usage(m),
  });
});

/** 학습 사진 후보 — 이 모델에 쓸 수 있는 식물(종 모델은 그 종만)과, plantId 를 주면 그 식물의 farmer 사진 */
r.get('/:id/candidates', (req, res) => {
  const m = modelRow(req.params.id);
  // 서버에 아직 없는 새 종 모델도 로컬에서 후보를 볼 수 있게 — 별칭을 쿼리로 받는다
  const extra = String(req.query.aliases || '').split(',').map((a) => a.trim()).filter(Boolean);
  const aliases = speciesAliasMap({ all: true });
  const kind = m?.kind ?? (req.query.kind === 'species' ? 'species' : 'detector');
  const own = new Set([normAlias(req.params.id), ...extra.map(normAlias)]);
  const plants = db.prepare(`SELECT p.id, p.name, p.species, p.roi_json, COUNT(ph.id) AS photos
      FROM plants p LEFT JOIN plant_photos ph ON ph.plant_id = p.id GROUP BY p.id ORDER BY photos DESC, p.name`).all()
    .filter((p) => {
      if (kind !== 'species') return true;
      const raw = p.species ?? p.name;
      return resolveSpecies(raw, aliases) === req.params.id || own.has(normAlias(raw));
    })
    .map((p) => ({ id: p.id, name: p.name, species: p.species, roi: parseJson(p.roi_json, null), photos: p.photos }));
  let photos = [];
  if (req.query.plantId) {
    photos = db.prepare('SELECT * FROM plant_photos WHERE plant_id=? ORDER BY COALESCE(taken_at, created_at) DESC LIMIT 200').all(req.query.plantId)
      .map((ph) => ({
        id: ph.id,
        plantId: ph.plant_id,
        takenAt: ph.taken_at || ph.created_at,
        url: `/api/photos/file/${ph.filename}`,
        thumbUrl: ph.thumb_filename ? `/api/photos/file/${ph.thumb_filename}` : `/api/photos/file/${ph.filename}`,
      }));
  }
  res.json({ plants, photos });
});

r.get('/:id/diagnosis-frames', (req, res) => {
  let sessions = [];
  try { sessions = db.prepare('SELECT * FROM diagnosis_sessions WHERE human_label IS NOT NULL ORDER BY created_at DESC').all(); } catch { sessions = []; }
  const aliases = speciesAliasMap({ all: true });
  const frames = [];
  for (const s of sessions) {
    const plant = db.prepare('SELECT name, species FROM plants WHERE id=?').get(s.plant_id);
    const target = s.model_id || resolveSpecies(plant?.species ?? plant?.name, aliases);
    if (target !== req.params.id) continue;
    for (const f of db.prepare('SELECT * FROM diagnosis_frames WHERE session_id=? ORDER BY frame_index').all(s.id)) {
      frames.push({
        sessionId: s.id, frameIndex: f.frame_index, plantId: s.plant_id, label: s.human_label,
        url: `/api/diagnosis/frame/${s.id}/${f.frame_index}`, createdAt: s.created_at,
      });
    }
  }
  res.json({ frames });
});

r.get('/:id/versions/:version/download', (req, res) => {
  const v = versionRow(req.params.id, Number(req.params.version));
  if (!v || !fs.existsSync(v.path)) return err(res, 404, 'version not found');
  res.set('Content-Disposition', `attachment; filename="${v.file}"`);
  res.type('application/octet-stream').send(fs.readFileSync(v.path));
});

r.get('/:id/versions/:version/dataset', (req, res) => {
  const v = versionRow(req.params.id, Number(req.params.version));
  if (!v) return err(res, 404, 'version not found');
  res.json({ dataset: parseJson(v.dataset_json, []) });
});

// ---------- 업로드 / 배포 (토큰) ----------

r.post('/:id/datasets/missing', requireToken, (req, res) => {
  const shas = req.body?.shas;
  if (!ID_RE.test(req.params.id)) return err(res, 400, 'invalid model id');
  if (!Array.isArray(shas) || shas.length > 1000 || !shas.every((s) => SHA_RE.test(s))) return err(res, 400, 'shas[<=1000] of sha256 hex required');
  res.json({ missing: shas.filter((s) => !fs.existsSync(datasetFile(req.params.id, s))) });
});

function cleanLabels(list) {
  if (!Array.isArray(list) || list.length < 2) return { error: 'model.labels must have at least 2 labels' };
  const out = [];
  for (const l of list) {
    const id = String(l?.id ?? '');
    if (!ID_RE.test(id) || out.some((x) => x.id === id)) return { error: `bad or duplicate label id '${id}'` };
    out.push({ id, ko: String(l.ko || id).slice(0, 40), en: String(l.en || l.ko || id).slice(0, 40) });
  }
  return { labels: out };
}

const isJpeg = (b) => b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;

r.post('/:id/releases', requireToken, upload.fields([{ name: 'model', maxCount: 1 }, { name: 'samples', maxCount: 3000 }]), (req, res) => {
  const id = req.params.id;
  if (!ID_RE.test(id) || id === 'watering') return err(res, 400, 'invalid model id');
  const manifest = parseJson(req.body?.manifest, null);
  if (!manifest?.model || !manifest?.version) return err(res, 400, 'manifest {model, version, dataset} required');
  const { model: mm, version: vv } = manifest;
  const dataset = Array.isArray(manifest.dataset) ? manifest.dataset : [];
  if (mm.id !== id) return err(res, 400, 'manifest.model.id must match the url');
  if (!['detector', 'species'].includes(mm.kind)) return err(res, 400, 'model.kind must be detector or species');
  const existing = modelRow(id);
  if (existing && existing.kind !== mm.kind) return err(res, 409, `model ${id} is a ${existing.kind} model`);
  if (!existing && BUILTIN_DETECTOR_IDS.includes(id)) return err(res, 409, 'builtin model missing on server');
  const cl = cleanLabels(mm.labels);
  if (cl.error) return err(res, 400, cl.error);
  const labelIds = cl.labels.map((l) => l.id);

  const n = vv.version;
  if (!Number.isInteger(n) || n < 2) return err(res, 400, 'version must be an integer >= 2 (v1 is the app-bundled model)');
  const vLabels = vv.labels;
  if (!Array.isArray(vLabels) || vLabels.length < 2 || !vLabels.every((l) => labelIds.includes(l))) {
    return err(res, 400, 'version.labels must be model label ids');
  }
  const file = req.files?.model?.[0];
  if (!file) return err(res, 400, 'model file required');
  let parsed;
  try { parsed = parseModel(file.buffer); } catch (e) { return err(res, 400, `invalid OJP1 model: ${e.message}`); }
  if (parsed.dense.out !== vLabels.length) return err(res, 400, `model has ${parsed.dense.out} outputs but version.labels has ${vLabels.length}`);
  if (parsed.size !== 48) return err(res, 400, 'model input size must be 48');
  const hash = sha256(file.buffer);
  if (vv.sha256 && vv.sha256 !== hash) return err(res, 400, 'version.sha256 does not match the model file');

  let aliases = null;
  if (mm.kind === 'species') {
    aliases = [...new Set((Array.isArray(mm.aliases) ? mm.aliases : []).filter(Boolean).map(String))];
    const map = speciesAliasMap({ all: true });
    const clash = aliases.find((a) => map[normAlias(a)] && map[normAlias(a)] !== id);
    if (clash) return err(res, 409, `alias '${clash}' already belongs to ${map[normAlias(clash)]}`);
  }

  const prev = versionRow(id, n);
  if (prev) {
    if (prev.sha256 === hash) return res.json({ modelId: id, version: n, sha256: hash, status: 'exists' });
    return err(res, 409, `version ${n} already exists on the server with a different model — sync the registry (git pull) first`);
  }

  // 학습·검증 사진 — 파일명 = sha256, 내용 검증 후 내용 주소로 보관
  for (const s of req.files?.samples || []) {
    const sha = path.basename(s.originalname, '.jpg');
    if (!SHA_RE.test(sha) || sha256(s.buffer) !== sha || !isJpeg(s.buffer)) return err(res, 400, `sample ${s.originalname} is not a JPEG named by its sha256`);
    const dest = datasetFile(id, sha);
    if (!fs.existsSync(dest)) {
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, s.buffer);
    }
  }
  const cleanDataset = [];
  for (const d of dataset) {
    if (!SHA_RE.test(d?.sha256 || '') || !labelIds.includes(d.label)) return err(res, 400, 'dataset entries need sha256 and a model label');
    cleanDataset.push({ sha256: d.sha256, label: d.label, split: d.split === 'val' ? 'val' : 'train', source: d.source || null, group: d.group || null });
  }
  const missing = cleanDataset.filter((d) => !fs.existsSync(datasetFile(id, d.sha256))).length;
  if (missing) return err(res, 400, `${missing} dataset photos are missing — upload them in samples`);

  const vfile = `${id}-v${n}.bin`;
  const dir = path.join(UPLOADED_DIR, id);
  fs.mkdirSync(dir, { recursive: true });
  const full = path.join(dir, vfile);
  fs.writeFileSync(full, file.buffer);

  const meta = [mm.name || id, mm.nameEn || null, mm.icon || (mm.kind === 'species' ? '🌱' : '🧠'), mm.description || null, mm.descriptionEn || null];
  db.exec('BEGIN');
  try {
    if (!existing) {
      db.prepare(`INSERT INTO ml_models(id, kind, name, name_en, icon, description, description_en, labels_json, aliases_json, base_model, updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,datetime('now'))`).run(id, mm.kind, ...meta, JSON.stringify(cl.labels), aliases ? JSON.stringify(aliases) : null, mm.baseModel || null);
    } else {
      // 라벨 이름·별칭은 로컬 레지스트리가 원본 — 서버 사본을 맞춘다 (라벨은 추가만 가능하니 기존 버전과 어긋나지 않음)
      const known = labelsMeta(existing).map((l) => l.id);
      if (!known.every((l) => labelIds.includes(l))) throw Object.assign(new Error('labels cannot be removed'), { status: 400 });
      db.prepare(`UPDATE ml_models SET name=?, name_en=?, icon=?, description=?, description_en=?, labels_json=?, aliases_json=?, updated_at=datetime('now') WHERE id=?`)
        .run(...meta, JSON.stringify(cl.labels), aliases ? JSON.stringify(aliases) : existing.aliases_json, id);
    }
    db.prepare(`INSERT INTO ml_model_versions(id, model_id, version, source, file, path, sha256, size, labels_json, parent_version,
        train_json, metrics_json, baseline_json, dataset_json, created_at, uploaded_at)
      VALUES(?,?,?,'uploaded',?,?,?,?,?,?,?,?,?,?,COALESCE(?, datetime('now')),datetime('now'))`).run(
      `${id}-v${n}`, id, n, vfile, full, hash, file.buffer.length, JSON.stringify(vLabels), Number.isInteger(vv.parentVersion) ? vv.parentVersion : null,
      JSON.stringify(vv.train ?? null), JSON.stringify(vv.metrics ?? null), JSON.stringify(vv.baseline ?? null), JSON.stringify(cleanDataset),
      typeof vv.createdAt === 'string' ? vv.createdAt.slice(0, 19).replace('T', ' ') : null,
    );
    db.exec('COMMIT');
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* noop */ }
    try { fs.rmSync(full); } catch { /* noop */ }
    return err(res, e.status || 500, e.message);
  }
  res.json({ modelId: id, version: n, sha256: hash, status: 'created', datasetSize: cleanDataset.length });
});

r.post('/:id/versions/:version/activate', requireToken, (req, res) => {
  const m = loadModel(req, res); if (!m) return;
  const v = versionRow(m.id, Number(req.params.version));
  if (!v) return err(res, 404, 'version not found on the server — upload it first');
  if (!fs.existsSync(v.path)) return err(res, 409, 'model file missing');
  db.prepare('UPDATE ml_models SET active_version=? WHERE id=?').run(v.version, m.id);
  db.prepare("UPDATE ml_model_versions SET activated_at=datetime('now') WHERE id=?").run(v.id);
  // farmer 들이 5분 주기를 기다리지 않고 새 모델을 받도록
  const slaves = db.prepare('SELECT DISTINCT slave_id FROM plants WHERE slave_id IS NOT NULL').all().map((x) => x.slave_id);
  for (const s of slaves) queueDetectorSync({ id: null, slave_id: s });
  res.json({ ok: true, modelId: m.id, version: v.version, sha256: v.sha256, notified: slaves.length });
});

export default r;
