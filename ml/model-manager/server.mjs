#!/usr/bin/env node
// OOJOO 로컬 model-manager — 이 PC 에서 학습 사진·라벨을 관리하고 모델을 학습·평가한다.
// 결과(모델·라벨·학습/검증 사진·지표)는 ml/registry/ 에 파일로 남고, 화면에서 서버(배포)와 GitHub 에 올린다.
//
//   cd ml/model-manager && npm install && npm start      → http://127.0.0.1:4900
//
// 로컬 전용: 127.0.0.1 에만 열고, 다른 사이트에서 오는 요청은 막는다(Host 검사 + 쓰기 요청에 x-mm-local 헤더 필요).
import express from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as store from './lib/store.js';
import * as jobs from './lib/jobs.js';
import * as gitOps from './lib/git.js';
import { remote, uploadVersion } from './lib/remote.js';
import { decodeImage, cropNormalized } from './lib/image.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const LOCAL_DIR = process.env.MM_LOCAL_DIR || path.join(HERE, '.local');
const CONFIG_FILE = path.join(LOCAL_DIR, 'config.json');
const DEFAULT_CONFIG = { serverUrl: 'http://34.50.40.104:4000', token: '', gitName: '', gitEmail: '' };

export function readConfig() {
  try { return { ...DEFAULT_CONFIG, ...JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')) }; } catch { return { ...DEFAULT_CONFIG }; }
}
function writeConfig(c) {
  fs.mkdirSync(LOCAL_DIR, { recursive: true });
  fs.writeFileSync(CONFIG_FILE, `${JSON.stringify(c, null, 1)}\n`);
}

const upload = multer({ storage: multer.memoryStorage(), limits: { files: 200, fileSize: 25 * 1024 * 1024 } });
const wrap = (fn) => (req, res) => Promise.resolve(fn(req, res)).catch((e) => res.status(e.status || 500).json({ error: e.message }));

export function createApp({ port = 4900 } = {}) {
  store.seed();
  const app = express();
  app.disable('x-powered-by');

  // 로컬 전용 가드 — DNS rebinding / 다른 사이트의 요청 위조 차단
  app.use((req, res, next) => {
    const host = String(req.headers.host || '');
    if (!/^(127\.0\.0\.1|localhost)(:\d+)?$/.test(host)) return res.status(403).send('local only');
    if (req.method !== 'GET' && req.get('x-mm-local') !== '1') return res.status(403).json({ error: 'missing x-mm-local header' });
    next();
  });
  app.use(express.json({ limit: '2mb' }));

  app.get('/', (req, res) => res.type('html').send(fs.readFileSync(path.join(HERE, 'ui/index.html'))));

  // ---------- 설정 / 서버 상태 ----------
  app.get('/api/config', (req, res) => {
    const c = readConfig();
    res.json({ serverUrl: c.serverUrl, hasToken: !!c.token, gitName: c.gitName, gitEmail: c.gitEmail, port });
  });
  app.put('/api/config', (req, res) => {
    const c = readConfig();
    const b = req.body || {};
    if (typeof b.serverUrl === 'string') c.serverUrl = b.serverUrl.trim();
    if (typeof b.token === 'string' && b.token) c.token = b.token.trim();
    if (b.clearToken) c.token = '';
    if (typeof b.gitName === 'string') c.gitName = b.gitName.trim();
    if (typeof b.gitEmail === 'string') c.gitEmail = b.gitEmail.trim();
    writeConfig(c);
    res.json({ ok: true });
  });

  // ---------- 모델 ----------
  app.get('/api/models', wrap(async (req, res) => {
    const cfg = readConfig();
    let server = null; let serverError = null;
    try { server = await remote(cfg).get('/api/models', { timeout: 6000 }); } catch (e) { serverError = e.message; }
    const srv = new Map((server?.models || []).map((m) => [m.id, m]));
    const models = store.listModels().map((m) => {
      const vs = store.versions(m.id);
      const latest = vs[0];
      const s = srv.get(m.id);
      const deployed = s?.activeVersion != null ? vs.find((v) => v.version === s.activeVersion) : null;
      const ref = deployed || latest;
      const health = localHealth(m, vs, s ?? null);
      return {
        id: m.id, kind: m.kind, icon: m.icon, name: m.name, nameEn: m.nameEn, labels: m.labels,
        latestVersion: latest?.version ?? null,
        serverVersion: s?.activeVersion ?? null,
        onServer: !!s,
        accuracy: ref?.metrics?.accuracy ?? null,
        metricsDataset: ref?.metrics?.dataset ?? null,
        unseenAccuracy: ref?.eval?.unseen?.accuracy ?? null,
        field: s?.field ?? null,
        samples: store.samples(m.id).length,
        usage: s?.usage ?? null,
        health,
      };
    });
    res.json({ models, unmatchedSpecies: server?.unmatchedSpecies ?? [], server: { url: cfg.serverUrl, reachable: !!server, error: serverError }, jobs: jobs.activeJobs() });
  }));

  app.post('/api/models', wrap((req, res) => res.json({ model: store.createModel(req.body || {}) })));

  app.get('/api/models/:id', wrap(async (req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    const cfg = readConfig();
    let server = null; let serverError = null;
    try { server = await remote(cfg).get(`/api/models/${m.id}`, { timeout: 6000 }); } catch (e) { serverError = e.status === 404 ? null : e.message; }
    let git = null;
    try { git = (await gitOps.status([m.id])); } catch (e) { git = { error: e.message }; }
    const vs = store.versions(m.id);
    const sv = new Map((server?.versions || []).map((v) => [v.version, v]));
    res.json({
      model: m,
      versions: vs.map((v) => {
        const s = sv.get(v.version);
        const { dataset, ...rest } = v;
        return {
          ...rest, datasetSize: dataset ? dataset.length : null,
          server: s ? { uploaded: true, active: !!s.active, sameFile: s.sha256 === v.sha256, uploadedAt: s.uploadedAt } : { uploaded: false },
        };
      }),
      samples: store.sampleCounts(m.id),
      server: server ? {
        reachable: true, activeVersion: server.model.activeVersion, field: server.field, agreement: server.agreement,
        usage: server.usage, health: server.health,
        versions: server.versions.map((v) => ({ version: v.version, sha256: v.sha256, active: v.active, source: v.source })),
      } : { reachable: false, error: serverError, missing: !serverError },
      git: git?.models ? { branch: git.branch, ...git.models[m.id] } : git,
      health: localHealth(m, vs, server ? { activeVersion: server.model.activeVersion, health: server.health } : null),
      jobs: jobs.jobsFor(m.id),
    });
  }));

  app.put('/api/models/:id', wrap((req, res) => res.json({ model: store.updateModel(req.params.id, req.body || {}) })));
  app.post('/api/models/:id/labels', wrap((req, res) => res.json({ model: store.addLabel(req.params.id, req.body || {}) })));

  // ---------- 학습 사진 ----------
  app.get('/api/models/:id/samples', (req, res) => {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 60, 1), 200);
    const offset = Math.max(Number.parseInt(req.query.offset, 10) || 0, 0);
    let list = store.samples(req.params.id).slice().reverse();
    if (req.query.label) list = list.filter((s) => s.label === req.query.label);
    res.json({ total: list.length, samples: list.slice(offset, offset + limit).map((s) => ({ ...s, url: `/api/models/${req.params.id}/samples/${s.id}/image` })) });
  });

  app.get('/api/models/:id/samples/:sid/image', (req, res) => {
    const s = store.samples(req.params.id).find((x) => x.id === req.params.sid);
    const f = s && store.samplePath(req.params.id, s.sha256);
    if (!f || !fs.existsSync(f)) return res.status(404).end();
    res.type('image/jpeg').send(fs.readFileSync(f));
  });

  app.post('/api/models/:id/samples', upload.array('photos', 200), wrap((req, res) => {
    const { label, note } = req.body || {};
    const files = req.files || [];
    if (!files.length) return res.status(400).json({ error: 'photos required' });
    let added = 0; let duplicates = 0;
    const rejected = [];
    for (const f of files) {
      try {
        const img = decodeImage(f.buffer);
        if (img.width < 16 || img.height < 16) throw new Error('too small (min 16px)');
        const r = store.addSample(req.params.id, { img, label, source: 'upload', note: note ? String(note).slice(0, 200) : null });
        if (r.status === 'duplicate') duplicates++; else added++;
      } catch (e) {
        if (e.status === 404 || /unknown label/.test(e.message)) throw e;
        rejected.push({ name: f.originalname, error: e.message });
      }
    }
    res.json({ added, duplicates, rejected });
  }));

  app.put('/api/models/:id/samples/:sid', wrap((req, res) => res.json({ sample: store.relabelSample(req.params.id, req.params.sid, req.body?.label) })));
  app.delete('/api/models/:id/samples/:sid', wrap((req, res) => res.json(store.deleteSample(req.params.id, req.params.sid))));

  // ---------- 서버에서 가져오기 (farmer 사진 RoI 크롭 / 진단 확정 프레임) ----------
  app.get('/api/models/:id/candidates', wrap(async (req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    const cfg = readConfig();
    const q = new URLSearchParams({ kind: m.kind, aliases: (m.aliases || []).join(',') });
    if (req.query.plantId) q.set('plantId', req.query.plantId);
    const c = await remote(cfg).get(`/api/models/${m.id}/candidates?${q}`);
    const base = remote(cfg).base;
    const imported = new Map(store.samples(m.id).filter((s) => s.source === 'farmer_photo').map((s) => [s.sourceRef, s.label]));
    res.json({
      plants: c.plants,
      photos: c.photos.map((p) => ({ ...p, url: base + p.url, thumbUrl: base + p.thumbUrl, importedAs: imported.get(p.id) ?? null })),
    });
  }));

  app.post('/api/models/:id/samples/import', wrap(async (req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    const { plantId, photos } = req.body || {};
    if (!plantId || !Array.isArray(photos) || !photos.length || photos.length > 100) return res.status(400).json({ error: 'plantId and photos[1..100] required' });
    const cfg = readConfig();
    const r = remote(cfg);
    const q = new URLSearchParams({ kind: m.kind, aliases: (m.aliases || []).join(','), plantId });
    const c = await r.get(`/api/models/${m.id}/candidates?${q}`);
    const plant = c.plants.find((p) => p.id === plantId);
    const byId = new Map(c.photos.map((p) => [p.id, p]));
    let imported = 0; let relabeled = 0;
    const failed = [];
    for (const it of photos) {
      const ph = byId.get(it.photoId);
      if (!ph) { failed.push({ photoId: it.photoId, error: 'photo not found' }); continue; }
      try {
        const already = store.samples(m.id).some((s) => s.source === 'farmer_photo' && s.sourceRef === ph.id);
        let img = null;
        if (!already) {
          img = decodeImage(await r.bytes(ph.url));
          if (plant?.roi) img = cropNormalized(img, plant.roi);
        }
        const out = store.addSample(m.id, {
          img, label: it.label, source: 'farmer_photo', sourceRef: ph.id,
          group: `plant:${plantId}:${String(ph.takenAt || '').slice(0, 10)}`, plantId, note: plant?.roi ? 'roi' : 'full frame',
        });
        if (out.status === 'relabeled') relabeled++; else imported++;
      } catch (e) {
        failed.push({ photoId: it.photoId, error: e.message });
      }
    }
    res.json({ imported, relabeled, failed });
  }));

  app.post('/api/models/:id/samples/import-diagnosis', wrap(async (req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    if (m.kind !== 'species') return res.status(400).json({ error: 'diagnosis frames are for species models' });
    const r = remote(readConfig());
    const { frames } = await r.get(`/api/models/${m.id}/diagnosis-frames`);
    const labels = m.labels.map((l) => l.id);
    const have = new Set(store.samples(m.id).filter((s) => s.source === 'diagnosis').map((s) => s.sourceRef));
    let imported = 0; let skipped = 0;
    for (const f of frames) {
      const ref = `${f.sessionId}:${f.frameIndex}`;
      if (have.has(ref) || !labels.includes(f.label)) { skipped++; continue; }
      try {
        const img = decodeImage(await r.bytes(f.url));
        store.addSample(m.id, { img, label: f.label, source: 'diagnosis', sourceRef: ref, group: `diag:${f.sessionId}`, plantId: f.plantId });
        imported++;
      } catch { skipped++; }
    }
    res.json({ imported, skipped });
  }));

  // ---------- 학습 / 평가 ----------
  app.post('/api/models/:id/train', wrap((req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    if (jobs.activeJobs().some((j) => j.modelId === m.id)) return res.status(409).json({ error: 'a job is already queued or running for this model' });
    const b = req.body || {};
    const epochs = b.epochs == null ? 15 : Number(b.epochs);
    const lr = b.lr == null ? 0.01 : Number(b.lr);
    const from = b.from === 'base' ? 'base' : (b.from == null || b.from === 'latest' ? undefined : Number(b.from));
    if (!Number.isInteger(epochs) || epochs < 1 || epochs > 60) return res.status(400).json({ error: 'epochs must be an integer 1..60' });
    if (!(lr >= 0.0005 && lr <= 0.1)) return res.status(400).json({ error: 'lr must be in 0.0005..0.1' });
    const parent = jobs.resolveParent(m, from);
    if (!parent) return res.status(400).json({ error: 'no version or base model to start from' });
    const counts = store.sampleCounts(m.id);
    const labels = m.labels.map((l) => l.id);
    const totalTrain = labels.reduce((a, l) => a + (counts[l]?.train ?? 0), 0);
    if (totalTrain < 5) return res.status(400).json({ error: `need at least 5 training photos (have ${totalTrain})` });
    const missing = labels.filter((l) => !parent.v.labels.includes(l) && (counts[l]?.train ?? 0) < 3);
    if (missing.length) return res.status(400).json({ error: `new labels need at least 3 training photos each: ${missing.join(', ')}` });
    res.json({ jobId: jobs.enqueue('train', m.id, { epochs, lr, from }).id });
  }));

  app.post('/api/models/:id/evaluate', wrap((req, res) => {
    const m = store.getModel(req.params.id);
    if (!m) return res.status(404).json({ error: 'model not found' });
    const version = req.body?.version != null ? Number(req.body.version) : store.latestVersion(m.id)?.version;
    if (version == null || !store.getVersion(m.id, version)) return res.status(400).json({ error: 'no version to evaluate' });
    if (!store.samples(m.id).length) return res.status(400).json({ error: 'no photos to evaluate on' });
    if (jobs.activeJobs().some((j) => j.modelId === m.id)) return res.status(409).json({ error: 'a job is already queued or running for this model' });
    res.json({ jobId: jobs.enqueue('evaluate', m.id, { version }).id });
  }));

  app.get('/api/jobs/:jobId', (req, res) => {
    const j = jobs.getJob(req.params.jobId);
    if (!j) return res.status(404).json({ error: 'job not found' });
    res.json(j);
  });

  app.get('/api/models/:id/versions/:v/download', (req, res) => {
    const v = store.getVersion(req.params.id, Number(req.params.v));
    if (!v) return res.status(404).end();
    res.set('Content-Disposition', `attachment; filename="${req.params.id}-v${v.version}.bin"`);
    res.type('application/octet-stream').send(fs.readFileSync(store.versionModelPath(req.params.id, v)));
  });

  // ---------- 서버 업로드 / 배포 ----------
  app.post('/api/models/:id/versions/:v/upload', wrap(async (req, res) => {
    res.json(await uploadVersion(readConfig(), req.params.id, Number(req.params.v), { activate: !!req.body?.activate }));
  }));

  app.post('/api/models/:id/versions/:v/activate', wrap(async (req, res) => {
    const cfg = readConfig();
    if (!cfg.token) return res.status(400).json({ error: '업로드 토큰이 설정되지 않았습니다' });
    res.json(await remote(cfg).post(`/api/models/${req.params.id}/versions/${Number(req.params.v)}/activate`, {}));
  }));

  // ---------- GitHub ----------
  app.get('/api/github/status', wrap(async (req, res) => {
    const ids = String(req.query.models || '').split(',').filter(Boolean);
    res.json(await gitOps.status(ids.length ? ids : store.listModels().map((m) => m.id)));
  }));

  app.post('/api/github/publish', wrap(async (req, res) => {
    const all = store.listModels().map((m) => m.id);
    const ids = (Array.isArray(req.body?.models) && req.body.models.length ? req.body.models : all).filter((id) => all.includes(id));
    const message = String(req.body?.message || '').trim() || `model-manager: ${ids.length === 1 ? ids[0] : `${ids.length} models`} — labels, photos, versions`;
    const cfg = readConfig();
    res.json(await gitOps.publish(ids, message, { name: cfg.gitName, email: cfg.gitEmail }));
  }));

  return app;
}

/**
 * 로컬 판정 (학습 데이터 쪽) + 서버 현장 신호.
 *  - 배포/최신 버전 이후 들어온 사진 10장 이상 → 재학습 가능
 *  - 아직 학습 안 된 라벨 → 재학습 필요
 *  - 새 사진 정확도 70% 미만 (평가 10장 이상) → 재학습 권장
 *  - 서버에 안 올린 버전 / 배포 버전보다 새 버전 → 안내
 */
function localHealth(m, vs, server) {
  const latest = vs[0];
  if (!latest) return { status: 'untrained', reasons: [{ level: 'info', code: 'no_version' }] };
  const reasons = [...(server?.health?.reasons || []).filter((r) => r.level === 'warn')];
  const ref = (server?.activeVersion != null && vs.find((v) => v.version === server.activeVersion)) || latest;
  const ev = ref.eval;
  if (ev?.unseen && ev.unseen.n >= 10 && ev.unseen.accuracy < 0.7) reasons.push({ level: 'warn', code: 'unseen_accuracy', accuracy: ev.unseen.accuracy, n: ev.unseen.n });
  const newSamples = store.samples(m.id).filter((s) => s.seq > (latest.train?.maxSampleSeq ?? 0)).length;
  if (newSamples >= 10) reasons.push({ level: 'info', code: 'new_samples', n: newSamples });
  const pending = m.labels.map((l) => l.id).filter((l) => !latest.labels.includes(l));
  if (pending.length) reasons.push({ level: 'info', code: 'new_labels', labels: pending });
  if (server && server.activeVersion != null && latest.version > server.activeVersion) reasons.push({ level: 'info', code: 'newer_than_server', version: latest.version, server: server.activeVersion });
  const status = reasons.some((r) => r.level === 'warn') ? 'warn' : reasons.length ? 'info' : 'ok';
  return { status, reasons };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.MM_PORT || 4900);
  createApp({ port }).listen(port, '127.0.0.1', () => {
    console.log(`OOJOO model-manager: http://127.0.0.1:${port}  (registry: ${store.REGISTRY})`);
  });
}
