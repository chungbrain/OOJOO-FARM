// model-manager API E2E (node:test) — 실제 학습 worker 까지 돌린다.
//  사진 추가 → 라벨 추가 → 학습(worker) → 성능·기준 비교 → 배포 → farmer 카탈로그 반영 → 롤백
//  farmer 사진 RoI 크롭 가져오기, 새 종 모델 / 새 검출 모듈, 현장 성능·재학습 권장, 토큰
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oojoo-mm-'));
process.env.DB_PATH = path.join(tmp, 'test.db');
process.env.ML_DATA_DIR = path.join(tmp, 'ml');

let server; let base; let image;

before(async () => {
  image = await import('../src/ml/image.js');
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
const req = async (method, p, body, headers = {}) => {
  const res = await fetch(base + p, { method, headers: { ...H, ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
};
const get = (p, h) => req('GET', p, undefined, h);
const post = (p, b, h) => req('POST', p, b ?? {}, h);
const sha = (b) => createHash('sha256').update(b).digest('hex');

// 합성 사진 (학습 루프 검증용): 초록 잎 + 패턴
let seed = 1;
const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
function photo(kind, w = 72, h = 72) {
  const data = new Uint8Array(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    const n = (rnd() - 0.5) * 30;
    data.set([50 + n, 150 + n, 50], i * 3);
  }
  const dot = (cx, cy, r, c) => {
    for (let y = cy - r; y <= cy + r; y++) for (let x = cx - r; x <= cx + r; x++) {
      if (x >= 0 && y >= 0 && x < w && y < h) data.set(c, (y * w + x) * 3);
    }
  };
  if (kind === 'severe') for (let i = 0; i < 25; i++) dot(Math.floor(rnd() * w), Math.floor(rnd() * h), 2, [12, 10, 8]);
  if (kind === 'aphid') for (let i = 0; i < 25; i++) dot(Math.floor(rnd() * w), Math.floor(rnd() * h), 1, [235, 235, 200]);
  if (kind === 'ripe') data.fill(0).forEach((_, i) => { if (i % 3 === 0) data[i] = 200; });
  return image.encodeJpeg({ width: w, height: h, data }, 92);
}

async function uploadPhotos(modelId, label, kind, n, extra = []) {
  const fd = new FormData();
  fd.append('label', label);
  for (let i = 0; i < n; i++) fd.append('photos', new Blob([photo(kind)], { type: 'image/jpeg' }), `${kind}${i}.jpg`);
  for (const [name, buf] of extra) fd.append('photos', new Blob([buf]), name);
  const res = await fetch(`${base}/api/models/${modelId}/samples`, { method: 'POST', body: fd });
  return { status: res.status, data: await res.json() };
}

async function waitJob(jobId) {
  for (let i = 0; i < 600; i++) {
    const { data } = await get(`/api/models/jobs/${jobId}`);
    if (data.status === 'done' || data.status === 'failed') return data;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('job timeout');
}

async function pairedPlant(name, species) {
  const u = (await post('/api/users', { nickname: 'mm', region: 'Seoul' })).data;
  const code = (await post('/api/pairing/code', { userId: u.id })).data.code;
  const v = (await post('/api/pairing/verify', { code, name: 'MM-Farmer' })).data;
  const p = (await post('/api/plants', { userId: u.id, slaveId: v.slaveId, name, species })).data;
  return { userId: u.id, slaveId: v.slaveId, key: v.sessionKey, plantId: p.plantId };
}

test('내장 모델 16개가 v1(앱 내장)로 등록되고 합성 검증 성능이 보인다', async () => {
  const { status, data } = await get('/api/models');
  assert.equal(status, 200);
  assert.equal(data.models.length, 16);
  const hg = data.models.find((m) => m.id === 'health_grade');
  assert.equal(hg.kind, 'detector');
  assert.equal(hg.activeVersion, 1);
  assert.equal(hg.activeSource, 'bundled');
  assert.equal(hg.metricsDataset, 'synthetic');
  assert.ok(hg.accuracy > 0.5 && hg.accuracy <= 1);
  assert.equal(data.models.filter((m) => m.kind === 'species').length, 14);

  const d = (await get('/api/models/basil')).data;
  assert.equal(d.versions.length, 1);
  assert.equal(d.versions[0].metrics.confusion.length, 6);
  assert.equal(d.health.status, 'ok');
});

test('해충 모델: 사진 추가 → 새 라벨 → 학습 → 기준 비교 → 배포/롤백이 farmer 카탈로그에 반영', async () => {
  const { slaveId, key, plantId } = await pairedPlant('바질', 'basil');

  const up = await uploadPhotos('pest', 'none', 'clean', 12, [['notes.txt', Buffer.from('hello')]]);
  assert.equal(up.status, 200);
  assert.equal(up.data.accepted, 12);
  assert.equal(up.data.rejected.length, 1);
  assert.match(up.data.rejected[0].error, /JPEG/);
  assert.equal((await uploadPhotos('pest', 'severe', 'severe', 12)).data.accepted, 12);
  assert.equal((await uploadPhotos('pest', 'nope', 'clean', 1)).status, 400);

  // 라벨 추가 — 사진이 3장 안 되면 학습 거부
  assert.equal((await post('/api/models/pest/labels', { id: 'aphid', ko: '진딧물', en: 'Aphids' })).status, 200);
  assert.equal((await post('/api/models/pest/labels', { id: 'aphid' })).status, 409);
  const refused = await post('/api/models/pest/train', { epochs: 3 });
  assert.equal(refused.status, 400);
  assert.match(refused.data.error, /aphid/);
  await uploadPhotos('pest', 'aphid', 'aphid', 12);

  let d = (await get('/api/models/pest')).data;
  assert.equal(d.samples.none.train + d.samples.none.val, 12);
  assert.ok(d.samples.none.val >= 1, '라벨별로 검증 사진이 생긴다');
  assert.deepEqual(d.health.reasons.map((r) => r.code).sort(), ['new_labels', 'new_samples']);

  const { data: { jobId } } = await post('/api/models/pest/train', { epochs: 4, lr: 0.02 });
  assert.equal((await post('/api/models/pest/train', {})).status, 409, '같은 모델 작업 중복 금지');
  const job = await waitJob(jobId);
  assert.equal(job.status, 'done', job.message);
  assert.equal(job.result.version, 2);
  assert.ok(job.result.nVal >= 3);

  d = (await get('/api/models/pest')).data;
  const v2 = d.versions.find((v) => v.version === 2);
  assert.equal(v2.source, 'trained');
  assert.equal(v2.active, false);
  assert.deepEqual(v2.labels, ['none', 'light', 'moderate', 'severe', 'aphid']);
  assert.equal(v2.metrics.dataset, 'samples');
  assert.equal(v2.metrics.confusion.length, 5);
  assert.equal(v2.baseline.from.version, 1, '같은 검증셋에서 배포 버전과 비교');
  assert.ok(v2.baseline.perClass.find((c) => c.label === 'aphid').recall === 0, '기존 모델은 새 라벨을 못 맞힌다');
  assert.ok(v2.metrics.accuracy >= v2.baseline.accuracy, `${v2.metrics.accuracy} vs ${v2.baseline.accuracy}`);
  assert.equal(v2.train.history.length <= 4, true);

  // 배포 전 farmer 카탈로그는 그대로
  let cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.equal(cat.labels.length, 4);
  const v1sha = cat.model.sha256;

  const act = await post('/api/models/pest/versions/2/activate');
  assert.equal(act.status, 200);
  assert.ok(act.data.notified >= 1);
  cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.deepEqual(cat.labels, ['none', 'light', 'moderate', 'severe', 'aphid']);
  assert.equal(cat.labelsKo[4], '진딧물');
  assert.equal(cat.model.url, '/api/detectors/models/pest-v2.bin');
  assert.deepEqual(cat.model.labels, cat.labels);
  const file = Buffer.from(await (await fetch(base + cat.model.url)).arrayBuffer());
  assert.equal(sha(file), cat.model.sha256);
  assert.equal(file.readInt32LE(8), 5, 'nclass 5');

  // farmer 에 즉시 재동기화 명령
  const pend = (await get(`/api/commands/pending/${slaveId}`, { 'x-session-key': key })).data.commands;
  assert.ok(pend.some((c) => c.action === 'detectors_sync'));
  // 새 라벨 결과를 서버가 받는다
  const r = await post('/api/detectors/results', { slaveId, plantId, results: [{ moduleId: 'pest', label: 'aphid', confidence: 0.8, modelSha: cat.model.sha256 }] }, { 'x-session-key': key });
  assert.deepEqual(r.data, { accepted: 1, skipped: 0 });

  // 롤백
  await post('/api/models/pest/versions/1/activate');
  cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'pest');
  assert.equal(cat.model.sha256, v1sha);
  assert.equal(cat.labels.length, 4);
  await post('/api/models/pest/versions/2/activate');

  // 평가 작업 — 배포 버전 이후 추가된 사진에서의 정확도
  await uploadPhotos('pest', 'severe', 'severe', 3);
  const ev = await waitJob((await post('/api/models/pest/evaluate')).data.jobId);
  assert.equal(ev.status, 'done', ev.message);
  d = (await get('/api/models/pest')).data;
  const e = d.versions.find((v) => v.version === 2).eval;
  assert.equal(e.unseen.n, 3);
  assert.equal(e.all.n, 39);
});

test('farmer 사진을 식물 RoI 로 잘라 학습 사진으로 가져오고, 다시 가져오면 라벨만 바뀐다', async () => {
  const { slaveId, key, plantId } = await pairedPlant('상추', 'lettuce');
  await fetch(`${base}/api/plants/plant/${plantId}`, { method: 'PUT', headers: H, body: JSON.stringify({ roi: { x: 0.5, y: 0, w: 0.5, h: 1 } }) });
  const img = { width: 120, height: 60, data: new Uint8Array(120 * 60 * 3).fill(120) };
  const fd = new FormData();
  fd.append('photo', new Blob([image.encodeJpeg(img)], { type: 'image/jpeg' }), 'p.jpg');
  fd.append('plantId', plantId);
  fd.append('takenAt', '2026-11-26 10:00:00');
  fd.append('location', 'farm');
  const upr = await fetch(`${base}/api/photos/upload/${slaveId}`, { method: 'POST', headers: { 'x-session-key': key }, body: fd });
  assert.equal(upr.status, 200);

  const c = (await get(`/api/models/lettuce/candidates?plantId=${plantId}`)).data;
  assert.ok(c.plants.some((p) => p.id === plantId && p.roi));
  assert.ok(!c.plants.some((p) => p.name === '바질'), '종 모델 후보는 그 종 식물만');
  assert.equal(c.photos.length, 1);
  const photoId = c.photos[0].id;

  const imp = await post('/api/models/lettuce/samples/import', { photos: [{ photoId, label: 'water_low' }] });
  assert.deepEqual(imp.data, { imported: 1, relabeled: 0, failed: [] });
  const s = (await get('/api/models/lettuce/samples')).data.samples[0];
  assert.deepEqual([s.width, s.height], [60, 60], 'RoI(오른쪽 절반)로 잘림');
  assert.equal(s.source, 'farmer_photo');
  const pic = await fetch(base + s.url);
  assert.equal(pic.headers.get('content-type'), 'image/jpeg');

  const again = await post('/api/models/lettuce/samples/import', { photos: [{ photoId, label: 'healthy' }] });
  assert.deepEqual(again.data, { imported: 0, relabeled: 1, failed: [] });
  assert.equal((await get(`/api/models/lettuce/candidates?plantId=${plantId}`)).data.photos[0].importedAs, 'healthy');

  // 라벨 수정 / 삭제
  assert.equal((await req('PUT', `/api/models/lettuce/samples/${s.id}`, { label: 'heat' })).status, 200);
  assert.equal((await req('DELETE', `/api/models/lettuce/samples/${s.id}`)).status, 200);
  assert.equal((await get('/api/models/lettuce/samples')).data.total, 0);
});

test('새 식물 종: 모델 없는 종이 후보로 보이고, 종 모델을 만들어 학습·배포하면 관수 모듈이 그 종을 찾는다', async () => {
  await pairedPlant('몬스테라', '몬스테라');
  let list = (await get('/api/models')).data;
  assert.ok(list.unmatchedSpecies.some((u) => u.raw === '몬스테라'));

  assert.equal((await post('/api/models', { id: 'monstera2', kind: 'species', name: '바질' })).status, 409, '다른 종의 별칭과 충돌');
  const created = await post('/api/models', { id: 'monstera', kind: 'species', name: '몬스테라', nameEn: 'Monstera', aliases: ['monstera deliciosa'], baseModel: 'herb' });
  assert.equal(created.status, 200);
  assert.equal(created.data.model.labels.length, 6, '기반 종과 같은 진단 라벨');
  assert.equal(created.data.model.activeVersion, null);
  let w = (await get('/api/detectors')).data.modules.find((m) => m.id === 'watering');
  assert.equal(w.speciesModels.monstera, undefined, '학습·배포 전에는 farmer 에 안 보인다');

  assert.equal((await post('/api/models/monstera/train', {})).status, 400, '사진 5장 미만');
  await uploadPhotos('monstera', 'healthy', 'clean', 4);
  await uploadPhotos('monstera', 'water_low', 'severe', 4);
  const job = await waitJob((await post('/api/models/monstera/train', { epochs: 2 })).data.jobId);
  assert.equal(job.status, 'done', job.message);
  const d = (await get('/api/models/monstera')).data;
  assert.equal(d.versions[0].train.from.modelId, 'herb', '기반 모델(herb)에서 시작');
  assert.equal(d.versions[0].metrics, null, '검증 사진이 없으면 검증 지표 없음');
  await post('/api/models/monstera/versions/1/activate');

  w = (await get('/api/detectors')).data.modules.find((m) => m.id === 'watering');
  assert.equal(w.speciesModels.monstera.url, '/api/detectors/models/monstera-v1.bin');
  assert.equal(w.speciesAliases['몬스테라'], 'monstera');
  assert.equal(w.speciesAliases.monsteradeliciosa, 'monstera');
  list = (await get('/api/models')).data;
  assert.ok(!list.unmatchedSpecies.some((u) => u.raw === '몬스테라'));
  assert.equal(list.models.find((m) => m.id === 'monstera').usage, 1);
});

test('새 검출 모듈: 만들고 학습·배포하면 farmer 카탈로그와 master 식물 카드에 생긴다', async () => {
  const { plantId } = await pairedPlant('딸기', 'strawberry');
  const c = await post('/api/models', {
    id: 'ripeness', kind: 'detector', name: '수확 시기', nameEn: 'Ripeness', icon: '🍓',
    labels: [{ id: 'unripe', ko: '덜 익음', en: 'Unripe' }, { id: 'ripe', ko: '익음', en: 'Ripe' }],
  });
  assert.equal(c.status, 200);
  assert.ok(!(await get('/api/detectors')).data.modules.some((m) => m.id === 'ripeness'));
  await uploadPhotos('ripeness', 'unripe', 'clean', 6);
  await uploadPhotos('ripeness', 'ripe', 'ripe', 6);
  const job = await waitJob((await post('/api/models/ripeness/train', { epochs: 3 })).data.jobId);
  assert.equal(job.status, 'done', job.message);
  await post('/api/models/ripeness/versions/1/activate');

  const mod = (await get('/api/detectors')).data.modules.find((m) => m.id === 'ripeness');
  assert.equal(mod.icon, '🍓');
  assert.deepEqual(mod.labelsKo, ['덜 익음', '익음']);
  assert.equal((await post(`/api/detectors/plant/${plantId}/ripeness`)).status, 200);
  const p = (await get(`/api/detectors/plant/${plantId}`)).data.modules.find((m) => m.id === 'ripeness');
  assert.equal(p.enabled, true);

  assert.equal((await req('DELETE', '/api/models/pest')).status, 400, '앱 내장 모델은 삭제 불가');
  assert.equal((await req('DELETE', '/api/models/ripeness')).status, 200);
  assert.ok(!(await get('/api/detectors')).data.modules.some((m) => m.id === 'ripeness'));
});

test('현장 성능: 저신뢰 판독이 많으면 재학습 권장, 버전별로 나눠 본다', async () => {
  const { slaveId, key, plantId } = await pairedPlant('고추', 'pepper');
  const cat = (await get('/api/detectors')).data.modules.find((m) => m.id === 'health_grade');
  for (let i = 0; i < 25; i++) {
    await post('/api/detectors/results', { slaveId, plantId, results: [{ moduleId: 'health_grade', label: 'fair', confidence: 0.42, modelSha: cat.model.sha256 }] }, { 'x-session-key': key });
  }
  const d = (await get('/api/models/health_grade')).data;
  assert.equal(d.field.recent.n, 25);
  assert.equal(d.field.recent.lowRate, 1);
  assert.equal(d.field.byVersion[0].version, 1);
  assert.equal(d.field.daily.length, 1);
  assert.equal(d.field.labels[0].label, 'fair');
  assert.equal(d.health.status, 'warn');
  assert.ok(d.health.reasons.some((r) => r.code === 'low_confidence'));
});

test('MODEL_MANAGER_TOKEN 이 있으면 토큰 없이 접근 불가', async () => {
  process.env.MODEL_MANAGER_TOKEN = 's3cret';
  try {
    assert.equal((await get('/api/models')).status, 401);
    assert.equal((await get('/api/models', { 'x-admin-token': 's3cret' })).status, 200);
    assert.equal((await get('/api/models?token=s3cret')).status, 200);
  } finally {
    delete process.env.MODEL_MANAGER_TOKEN;
  }
  const page = await fetch(`${base}/model-manager`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /html/);
});
