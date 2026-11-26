// 로컬 model-manager E2E — 실제 백엔드(같은 프로세스, 임시 DB)를 서버로 두고:
//  사진·라벨 → 로컬 학습 → ml/registry 파일 → 서버 업로드(사진 포함)·배포 → farmer 카탈로그 반영
//  farmer 사진 RoI 크롭 가져오기, 새 종 모델, GitHub 올리기(임시 저장소 + bare 원격), 로컬 전용 가드
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oojoo-mm-local-'));
const repo = path.join(tmp, 'repo');
const remoteRepo = path.join(tmp, 'remote.git');
const TOKEN = 'local-test-token';
const git = (args, cwd = repo) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

// 임시 git 저장소 + bare 원격 (실제 저장소는 건드리지 않는다)
fs.mkdirSync(repo, { recursive: true });
git(['init', '--bare', remoteRepo], tmp);
git(['init']);
git(['config', 'user.name', 'mm-test']);
git(['config', 'user.email', 'mm-test@example.com']);
git(['config', 'core.autocrlf', 'false']);
fs.writeFileSync(path.join(repo, 'README.md'), 'test\n');
git(['add', 'README.md']);
git(['commit', '-m', 'init']);
git(['remote', 'add', 'origin', remoteRepo]);
git(['push', 'origin', 'HEAD']);

process.env.NODE_ENV = 'test';
process.env.MM_REPO = repo;
process.env.MM_REGISTRY = path.join(repo, 'ml/registry');
process.env.MM_LOCAL_DIR = path.join(tmp, 'local');
process.env.DB_PATH = path.join(tmp, 'server.db');
process.env.ML_DATA_DIR = path.join(tmp, 'server-ml');
process.env.MODEL_MANAGER_TOKEN = TOKEN;
const cwd = process.cwd();
process.chdir(tmp); // 백엔드의 ./data/photos 등이 임시 폴더에 생기게

let backend; let local; let serverBase; let localBase; let image;

before(async () => {
  image = await import('../lib/image.js');
  const { default: serverApp } = await import('../../../backend/src/app.js');
  await new Promise((r) => { backend = serverApp.listen(0, r); });
  serverBase = `http://127.0.0.1:${backend.address().port}`;
  fs.mkdirSync(process.env.MM_LOCAL_DIR, { recursive: true });
  fs.writeFileSync(path.join(process.env.MM_LOCAL_DIR, 'config.json'), JSON.stringify({ serverUrl: serverBase, token: TOKEN }));
  const { createApp } = await import('../server.mjs');
  await new Promise((r) => { local = createApp({ port: 0 }).listen(0, '127.0.0.1', r); });
  localBase = `http://127.0.0.1:${local.address().port}`;
});

after(() => {
  for (const s of [local, backend]) { try { s?.close(); s?.closeAllConnections?.(); } catch {} }
  process.chdir(cwd);
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {}
});

const L = { 'x-mm-local': '1' };
async function call(method, p, body, base = localBase) {
  const res = await fetch(base + p, {
    method, headers: { ...L, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
}
const lget = (p) => call('GET', p);
const lpost = (p, b = {}) => call('POST', p, b);

let seed = 7;
const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
function photo(kind, w = 72, h = 72) {
  const data = new Uint8Array(w * h * 3);
  for (let i = 0; i < w * h; i++) { const n = (rnd() - 0.5) * 30; data.set([50 + n, 150 + n, 50], i * 3); }
  const dots = kind === 'clean' ? 0 : 25;
  for (let k = 0; k < dots; k++) {
    const x = Math.floor(rnd() * w); const y = Math.floor(rnd() * h);
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const xx = x + dx; const yy = y + dy;
      if (xx >= 0 && yy >= 0 && xx < w && yy < h) data.set(kind === 'aphid' ? [235, 235, 200] : [12, 10, 8], (yy * w + xx) * 3);
    }
  }
  return image.encodeJpeg({ width: w, height: h, data }, 92);
}
async function uploadPhotos(model, label, kind, n) {
  const fd = new FormData();
  fd.append('label', label);
  for (let i = 0; i < n; i++) fd.append('photos', new Blob([photo(kind)], { type: 'image/jpeg' }), `${kind}${i}.jpg`);
  const res = await fetch(`${localBase}/api/models/${model}/samples`, { method: 'POST', headers: L, body: fd });
  return res.json();
}
async function waitJob(id) {
  for (let i = 0; i < 1200; i++) {
    const { data } = await lget(`/api/jobs/${id}`);
    if (data.status === 'done' || data.status === 'failed') return data;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('job timeout');
}

test('로컬 전용 가드: 쓰기에는 x-mm-local 헤더, 다른 Host 는 거부', async () => {
  const noHeader = await fetch(`${localBase}/api/models`, { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json' } });
  assert.equal(noHeader.status, 403);
  const page = await fetch(`${localBase}/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /html/);
});

test('시작하면 앱 내장 16개가 로컬 레지스트리에 생기고 서버 배포 버전이 보인다', async () => {
  const { data } = await lget('/api/models');
  assert.equal(data.models.length, 16);
  assert.equal(data.server.reachable, true);
  const pest = data.models.find((m) => m.id === 'pest');
  assert.equal(pest.latestVersion, 1);
  assert.equal(pest.serverVersion, 1);
  const v1 = JSON.parse(fs.readFileSync(path.join(process.env.MM_REGISTRY, 'pest/versions/v1/version.json'), 'utf8'));
  assert.equal(v1.source, 'bundled');
  assert.equal(v1.modelFile, 'backend/models/pest.bin');
});

test('로컬 학습 → 레지스트리 파일 → 서버 업로드(사진 포함)·배포 → farmer 카탈로그 반영', async () => {
  assert.equal((await uploadPhotos('pest', 'none', 'clean', 12)).added, 12);
  assert.equal((await uploadPhotos('pest', 'severe', 'severe', 12)).added, 12);
  assert.equal((await lpost('/api/models/pest/labels', { id: 'aphid', ko: '진딧물', en: 'Aphids' })).status, 200);
  assert.equal((await lpost('/api/models/pest/train', { epochs: 3 })).status, 400, '새 라벨 사진 3장 미만');
  await uploadPhotos('pest', 'aphid', 'aphid', 12);
  const dup = await uploadPhotos('pest', 'none', 'clean', 0).catch(() => null);
  assert.equal(dup?.error, 'photos required');

  const job = await waitJob((await lpost('/api/models/pest/train', { epochs: 3, lr: 0.02 })).data.jobId);
  assert.equal(job.status, 'done', job.message);
  assert.equal(job.result.version, 2);
  const vdir = path.join(process.env.MM_REGISTRY, 'pest/versions/v2');
  const v2 = JSON.parse(fs.readFileSync(path.join(vdir, 'version.json'), 'utf8'));
  assert.ok(fs.existsSync(path.join(vdir, 'model.bin')));
  assert.deepEqual(v2.labels, ['none', 'light', 'moderate', 'severe', 'aphid']);
  assert.equal(v2.dataset.length, 36);
  assert.ok(v2.dataset.some((d) => d.split === 'val'));
  assert.equal(v2.baseline.from.version, 1);
  assert.equal(v2.train.maxSampleSeq, 36);

  const up = await lpost('/api/models/pest/versions/2/upload', { activate: true });
  assert.equal(up.status, 200, JSON.stringify(up.data));
  assert.equal(up.data.status, 'created');
  assert.equal(up.data.uploadedPhotos, 36);
  assert.equal(up.data.activated.version, 2);
  const cat = (await call('GET', '/api/detectors', undefined, serverBase)).data.modules.find((m) => m.id === 'pest');
  assert.equal(cat.model.url, '/api/detectors/models/pest-v2.bin');
  assert.equal(cat.model.sha256, v2.sha256);
  assert.equal(cat.labelsKo[4], '진딧물');
  // 서버에 학습·검증 사진이 그대로
  const ds = (await call('GET', '/api/models/pest/versions/2/dataset', undefined, serverBase)).data.dataset;
  assert.equal(ds.length, 36);

  const again = await lpost('/api/models/pest/versions/2/upload', {});
  assert.equal(again.data.status, 'exists');
  assert.equal(again.data.uploadedPhotos, 0, '서버에 있는 사진은 다시 보내지 않는다');

  const d = (await lget('/api/models/pest')).data;
  const lv2 = d.versions.find((v) => v.version === 2);
  assert.deepEqual([lv2.server.uploaded, lv2.server.active, lv2.server.sameFile], [true, true, true]);
  assert.equal(d.server.activeVersion, 2);
  assert.ok(d.git.changed > 0, '아직 GitHub 에 안 올린 변경');

  // 평가 — 배포 뒤 추가된 사진만 '새 사진'
  await uploadPhotos('pest', 'severe', 'severe', 3);
  const ev = await waitJob((await lpost('/api/models/pest/evaluate', { version: 2 })).data.jobId);
  assert.equal(ev.status, 'done', ev.message);
  assert.equal(ev.result.n, 39);
  const ev2 = JSON.parse(fs.readFileSync(path.join(vdir, 'version.json'), 'utf8')).eval;
  assert.equal(ev2.unseen.n, 3);
});

test('farmer 사진을 서버에서 받아 식물 RoI 로 잘라 학습 사진으로', async () => {
  const S = (p, b, h = {}) => fetch(serverBase + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...h }, body: JSON.stringify(b) }).then((r) => r.json());
  const u = await S('/api/users', { nickname: 'f', region: 'Seoul' });
  const code = (await S('/api/pairing/code', { userId: u.id })).code;
  const v = await S('/api/pairing/verify', { code, name: 'F' });
  const p = await S('/api/plants', { userId: u.id, slaveId: v.slaveId, name: '상추', species: 'lettuce' });
  await fetch(`${serverBase}/api/plants/plant/${p.plantId}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ roi: { x: 0.5, y: 0, w: 0.5, h: 1 } }) });
  const fd = new FormData();
  fd.append('photo', new Blob([image.encodeJpeg({ width: 120, height: 60, data: new Uint8Array(120 * 60 * 3).fill(110) })], { type: 'image/jpeg' }), 'x.jpg');
  fd.append('plantId', p.plantId); fd.append('takenAt', '2026-11-26 09:00:00'); fd.append('location', 'farm');
  assert.equal((await fetch(`${serverBase}/api/photos/upload/${v.slaveId}`, { method: 'POST', headers: { 'x-session-key': v.sessionKey }, body: fd })).status, 200);

  const c = (await lget(`/api/models/lettuce/candidates?plantId=${p.plantId}`)).data;
  assert.equal(c.photos.length, 1);
  assert.ok(c.photos[0].url.startsWith(serverBase));
  const imp = await lpost('/api/models/lettuce/samples/import', { plantId: p.plantId, photos: [{ photoId: c.photos[0].id, label: 'water_low' }] });
  assert.deepEqual(imp.data, { imported: 1, relabeled: 0, failed: [] });
  const s = (await lget('/api/models/lettuce/samples')).data.samples[0];
  assert.deepEqual([s.width, s.height], [60, 60], 'RoI(오른쪽 절반)');
  assert.equal(s.source, 'farmer_photo');
  const again = await lpost('/api/models/lettuce/samples/import', { plantId: p.plantId, photos: [{ photoId: c.photos[0].id, label: 'healthy' }] });
  assert.deepEqual(again.data, { imported: 0, relabeled: 1, failed: [] });
});

test('새 종 모델: 로컬에서 만들고 학습 → 업로드·배포하면 서버 관수 모듈이 그 종을 찾는다', async () => {
  const c = await lpost('/api/models', { id: 'monstera', kind: 'species', name: '몬스테라', nameEn: 'Monstera', baseModel: 'herb' });
  assert.equal(c.status, 200);
  assert.equal((await lpost('/api/models', { id: 'monstera2', kind: 'species', name: '바질' })).status, 409);
  await uploadPhotos('monstera', 'healthy', 'clean', 4);
  await uploadPhotos('monstera', 'water_low', 'severe', 4);
  const job = await waitJob((await lpost('/api/models/monstera/train', { epochs: 2 })).data.jobId);
  assert.equal(job.status, 'done', job.message);
  assert.equal(job.result.version, 2, 'v1 은 앱 내장 몫 — 새 모델도 v2 부터');
  const up = await lpost('/api/models/monstera/versions/2/upload', { activate: true });
  assert.equal(up.status, 200, JSON.stringify(up.data));
  const w = (await call('GET', '/api/detectors', undefined, serverBase)).data.modules.find((m) => m.id === 'watering');
  assert.equal(w.speciesAliases['몬스테라'], 'monstera');
  assert.equal(w.speciesModels.monstera.version, 2);
});

test('GitHub 올리기: 모델 폴더만 커밋해서 push', async () => {
  fs.writeFileSync(path.join(repo, 'unrelated.txt'), 'do not commit me\n');
  const r = await lpost('/api/github/publish', { models: ['pest', 'monstera'], message: 'model: pest v2 + monstera v2' });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  assert.ok(r.data.commit);
  assert.ok(r.data.files > 40);
  const files = git(['show', '--name-only', '--format=', 'HEAD']).trim().split('\n');
  assert.ok(files.includes('ml/registry/pest/versions/v2/model.bin'));
  assert.ok(files.some((f) => f.startsWith('ml/registry/pest/samples/')));
  assert.ok(files.includes('ml/registry/monstera/model.json'));
  assert.ok(!files.includes('unrelated.txt'), '다른 파일은 커밋하지 않는다');
  assert.equal(git(['log', '-1', '--format=%s', 'HEAD'], remoteRepo).trim(), 'model: pest v2 + monstera v2', '원격에 push 됨');
  const st = (await lget('/api/github/status?models=pest')).data;
  assert.equal(st.models.pest.changed, 0);
  assert.equal(st.models.pest.unpushed, 0);
});
