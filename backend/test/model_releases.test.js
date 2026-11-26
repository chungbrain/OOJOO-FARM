// 서버 모델 API (/api/models) — 로컬 model-manager 가 올리는 릴리스(모델 + 라벨 + 학습/검증 사진)의
// 검증·보관·배포, farmer 카탈로그 반영, 현장 성능/후보 사진 조회, 토큰.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.NODE_ENV = 'test';
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oojoo-rel-'));
process.env.DB_PATH = path.join(tmp, 'test.db');
process.env.ML_DATA_DIR = path.join(tmp, 'ml');
const TOKEN = 'release-token';
const MODELS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../models');

let server; let base; let ojp1;

before(async () => {
  ojp1 = await import('../src/ml/ojp1.js');
  const { default: app } = await import('../src/app.js');
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  try { server?.closeAllConnections?.(); } catch {}
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {}
});

const H = { 'Content-Type': 'application/json' };
const auth = { 'x-admin-token': TOKEN };
async function req(method, p, body, headers = {}) {
  const res = await fetch(base + p, { method, headers: { ...H, ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
}
const get = (p) => req('GET', p);
const post = (p, b, h) => req('POST', p, b ?? {}, h);
const sha = (b) => createHash('sha256').update(b).digest('hex');
const fakeJpeg = () => Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), randomBytes(200)]);

const PEST = ['none', 'light', 'moderate', 'severe'];
const pestLabels = [
  { id: 'none', ko: '해충없음', en: 'None' }, { id: 'light', ko: '경미발생', en: 'Light' },
  { id: 'moderate', ko: '보통발생', en: 'Moderate' }, { id: 'severe', ko: '심각발생', en: 'Severe' },
  { id: 'aphid', ko: '진딧물', en: 'Aphids' },
];

function release({ id = 'pest', kind = 'detector', version = 2, labels = pestLabels, outLabels = [...PEST, 'aphid'], parentFile = 'pest.bin', parentLabels = PEST, samples = [], extraModel = {}, seed = 1 } = {}) {
  const parent = ojp1.parseModel(fs.readFileSync(path.join(MODELS, parentFile)));
  const bytes = ojp1.serializeModel(ojp1.adaptModel(parent, parentLabels, outLabels, seed));
  const dataset = samples.map((s) => ({ sha256: sha(s.bytes), label: s.label, split: s.split || 'train', source: 'upload', group: s.group || 'g' }));
  const manifest = {
    model: { id, kind, icon: '🐛', name: '해충 검출', nameEn: 'Pest detection', labels, aliases: [], ...extraModel },
    version: { version, parentVersion: 1, labels: outLabels, sha256: sha(bytes), metrics: { dataset: 'samples', accuracy: 0.9, n: 2 }, baseline: { accuracy: 0.5 }, train: { epochs: 3 }, createdAt: '2026-11-26 12:00:00' },
    dataset,
  };
  return { bytes, manifest, dataset };
}

async function sendRelease(id, { bytes, manifest }, files = [], headers = auth) {
  const fd = new FormData();
  fd.append('manifest', JSON.stringify(manifest));
  fd.append('model', new Blob([bytes]), `${id}.bin`);
  for (const f of files) fd.append('samples', new Blob([f.bytes], { type: 'image/jpeg' }), f.name ?? `${sha(f.bytes)}.jpg`);
  const res = await fetch(`${base}/api/models/${id}/releases`, { method: 'POST', headers, body: fd });
  return { status: res.status, data: await res.json() };
}

async function pairedPlant(name, species) {
  const u = (await post('/api/users', { nickname: 'rel', region: 'Seoul' })).data;
  const code = (await post('/api/pairing/code', { userId: u.id })).data.code;
  const v = (await post('/api/pairing/verify', { code, name: 'Rel-Farmer' })).data;
  const p = (await post('/api/plants', { userId: u.id, slaveId: v.slaveId, name, species })).data;
  return { slaveId: v.slaveId, key: v.sessionKey, plantId: p.plantId };
}

test('앱 내장 모델 16개가 v1 로 등록되고 목록에 합성 검증 성능이 보인다', async () => {
  const { status, data } = await get('/api/models');
  assert.equal(status, 200);
  assert.equal(data.models.length, 16);
  const hg = data.models.find((m) => m.id === 'health_grade');
  assert.equal(hg.activeVersion, 1);
  assert.equal(hg.metricsDataset, 'synthetic');
  assert.ok(hg.accuracy > 0.5);
  const d = (await get('/api/models/basil')).data;
  assert.equal(d.versions[0].source, 'bundled');
  assert.equal(d.versions[0].metrics.confusion.length, 6);
});

test('쓰기는 서버 토큰 필수 — 미설정이면 업로드 불가, 틀리면 401', async () => {
  delete process.env.MODEL_MANAGER_TOKEN;
  assert.equal((await post('/api/models/pest/datasets/missing', { shas: [] }, auth)).status, 403);
  process.env.MODEL_MANAGER_TOKEN = TOKEN;
  assert.equal((await post('/api/models/pest/datasets/missing', { shas: [] }, { 'x-admin-token': 'nope' })).status, 401);
  assert.equal((await post('/api/models/pest/versions/1/activate')).status, 401);
});

test('릴리스 업로드: 사진은 없는 것만 받고, 배포하면 farmer 카탈로그가 새 모델·라벨을 가리킨다', async () => {
  process.env.MODEL_MANAGER_TOKEN = TOKEN;
  const { slaveId, key } = await pairedPlant('바질', 'basil');
  const photos = [{ bytes: fakeJpeg(), label: 'none' }, { bytes: fakeJpeg(), label: 'aphid' }, { bytes: fakeJpeg(), label: 'aphid', split: 'val' }];
  const rel = release({ samples: photos });

  const miss = await post('/api/models/pest/datasets/missing', { shas: rel.dataset.map((d) => d.sha256) }, auth);
  assert.equal(miss.data.missing.length, 3);
  // 사진 빠지면 거부
  const noPhotos = await sendRelease('pest', rel, []);
  assert.equal(noPhotos.status, 400);
  assert.match(noPhotos.data.error, /missing/);

  const up = await sendRelease('pest', rel, photos);
  assert.equal(up.status, 200, JSON.stringify(up.data));
  assert.equal(up.data.status, 'created');
  assert.equal(up.data.datasetSize, 3);
  assert.deepEqual((await post('/api/models/pest/datasets/missing', { shas: rel.dataset.map((d) => d.sha256) }, auth)).data.missing, []);

  // 같은 파일 다시 → exists, 다른 파일 같은 버전 → 409
  assert.equal((await sendRelease('pest', rel, [])).data.status, 'exists');
  const other = release({ samples: photos, seed: 99 });
  assert.equal((await sendRelease('pest', other, [])).status, 409);

  const d = (await get('/api/models/pest')).data;
  const v2 = d.versions.find((v) => v.version === 2);
  assert.equal(v2.source, 'uploaded');
  assert.equal(v2.datasetSize, 3);
  assert.equal(v2.metrics.accuracy, 0.9);
  assert.equal(d.model.labels.find((l) => l.id === 'aphid').ko, '진딧물');
  assert.equal((await get('/api/models/pest/versions/2/dataset')).data.dataset.length, 3);

  // 배포 전 카탈로그는 그대로
  let cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.equal(cat.labels.length, 4);
  const act = await post('/api/models/pest/versions/2/activate', {}, auth);
  assert.equal(act.status, 200);
  cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.deepEqual(cat.labels, [...PEST, 'aphid']);
  assert.equal(cat.labelsKo[4], '진딧물');
  assert.equal(cat.model.url, '/api/detectors/models/pest-v2.bin');
  const file = Buffer.from(await (await fetch(base + cat.model.url)).arrayBuffer());
  assert.equal(sha(file), rel.manifest.version.sha256);
  const pend = (await req('GET', `/api/commands/pending/${slaveId}`, undefined, { 'x-session-key': key })).data.commands;
  assert.ok(pend.some((c) => c.action === 'detectors_sync'));

  // 롤백
  await post('/api/models/pest/versions/1/activate', {}, auth);
  cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.equal(cat.labels.length, 4);
});

test('릴리스 검증: 출력 수·버전 번호·라벨 삭제·사진 이름·종류', async () => {
  process.env.MODEL_MANAGER_TOKEN = TOKEN;
  const bad1 = release({ version: 3, outLabels: [...PEST, 'aphid'] });
  bad1.manifest.version.labels = PEST; // 모델은 5출력
  assert.match((await sendRelease('pest', bad1)).data.error, /outputs/);
  assert.equal((await sendRelease('pest', release({ version: 1 }))).status, 400, 'v1 은 앱 내장');
  const removed = release({ version: 3, labels: pestLabels.slice(1), outLabels: ['light', 'moderate', 'severe', 'aphid'] });
  assert.equal((await sendRelease('pest', removed)).status, 400, '라벨 삭제 불가');
  const photo = { bytes: fakeJpeg(), label: 'none' };
  const r = release({ version: 3, samples: [photo] });
  assert.equal((await sendRelease('pest', r, [{ bytes: photo.bytes, name: `${'0'.repeat(64)}.jpg` }])).status, 400, 'sha 불일치');
  assert.equal((await sendRelease('pest', release({ version: 3, kind: 'species' }))).status, 409, '종류 변경 불가');
});

test('새 종 모델 릴리스 → 배포하면 관수 모듈이 그 종 이름으로 찾고, 후보 사진은 별칭으로 조회', async () => {
  process.env.MODEL_MANAGER_TOKEN = TOKEN;
  const { plantId } = await pairedPlant('거실 몬스테라', '몬스테라');
  let list = (await get('/api/models')).data;
  assert.ok(list.unmatchedSpecies.some((u) => u.raw === '몬스테라'));
  // 서버에 아직 없는 종도 로컬이 별칭으로 후보 식물을 볼 수 있다
  const c = (await get(`/api/models/monstera/candidates?kind=species&aliases=${encodeURIComponent('몬스테라')}`)).data;
  assert.ok(c.plants.some((p) => p.id === plantId));

  const speciesLabels = ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat'];
  const r = release({
    id: 'monstera', kind: 'species', parentFile: 'herb.bin', parentLabels: speciesLabels, outLabels: speciesLabels,
    labels: speciesLabels.map((id) => ({ id, ko: id, en: id })), extraModel: { name: '몬스테라', aliases: ['몬스테라', 'Monstera'] },
  });
  const clash = structuredClone(r);
  clash.manifest.model.aliases = ['바질'];
  assert.equal((await sendRelease('monstera', clash)).status, 409, '다른 종 별칭 충돌');
  assert.equal((await sendRelease('monstera', r)).data.status, 'created');
  assert.equal((await post('/api/models/monstera/versions/2/activate', {}, auth)).status, 200);
  const w = (await get('/api/detectors')).data.modules.find((m) => m.id === 'watering');
  assert.equal(w.speciesModels.monstera.url, '/api/detectors/models/monstera-v2.bin');
  assert.equal(w.speciesAliases['몬스테라'], 'monstera');
  list = (await get('/api/models')).data;
  assert.ok(!list.unmatchedSpecies.some((u) => u.raw === '몬스테라'));
  assert.equal(list.models.find((m) => m.id === 'monstera').usage, 1);
});

test('farmer 사진 후보 + 현장 성능(저신뢰 → 재학습 권장)', async () => {
  const { slaveId, key, plantId } = await pairedPlant('상추', 'lettuce');
  await fetch(`${base}/api/plants/plant/${plantId}`, { method: 'PUT', headers: H, body: JSON.stringify({ roi: { x: 0.5, y: 0, w: 0.5, h: 1 } }) });
  const fd = new FormData();
  fd.append('photo', new Blob([fakeJpeg()], { type: 'image/jpeg' }), 'p.jpg');
  fd.append('plantId', plantId); fd.append('takenAt', '2026-11-26 10:00:00'); fd.append('location', 'farm');
  assert.equal((await fetch(`${base}/api/photos/upload/${slaveId}`, { method: 'POST', headers: { 'x-session-key': key }, body: fd })).status, 200);
  const c = (await get(`/api/models/lettuce/candidates?plantId=${plantId}`)).data;
  assert.deepEqual(c.plants.find((p) => p.id === plantId).roi, { x: 0.5, y: 0, w: 0.5, h: 1 });
  assert.equal(c.photos.length, 1);
  assert.match(c.photos[0].url, /^\/api\/photos\/file\//);
  assert.ok(!c.plants.some((p) => p.name === '바질'), '종 모델 후보는 그 종 식물만');

  const cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'health_grade');
  for (let i = 0; i < 25; i++) {
    await post('/api/detectors/results', { slaveId, plantId, results: [{ moduleId: 'health_grade', label: 'fair', confidence: 0.4, modelSha: cat.model.sha256 }] }, { 'x-session-key': key });
  }
  const d = (await get('/api/models/health_grade')).data;
  assert.equal(d.field.recent.n, 25);
  assert.equal(d.field.byVersion[0].version, 1);
  assert.equal(d.health.status, 'warn');
  assert.deepEqual((await get('/api/models/basil/diagnosis-frames')).data, { frames: [] });
});
