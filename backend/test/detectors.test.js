// 온디바이스 검출 모듈 계약 테스트 (node:test)
//  - 카탈로그/모델 다운로드(sha256·ETag·경로 차단)
//  - 식물별 모듈 추가/삭제 → farmer 재동기화 명령(중복 없음) + 식물 목록 detectors 반영
//  - farmer 결과/상태 보고 → master 조회·SSE, 권한/검증/삭제 경합
// 실행: npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-detectors-test-${process.pid}.db`);
process.env.DB_PATH = tmpDb;
const MODELS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../models');

let server;
let base;

before(async () => {
  const { default: app } = await import('../src/app.js');
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  try { server?.closeAllConnections?.(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

const json = { 'Content-Type': 'application/json' };
const post = (p, body, headers = {}) => fetch(base + p, { method: 'POST', headers: { ...json, ...headers }, body: JSON.stringify(body) });
const del = (p) => fetch(base + p, { method: 'DELETE' });
const get = (p, headers = {}) => fetch(base + p, { headers });

async function pairedPlant(species = 'basil') {
  const u = await (await post('/api/users', { nickname: '모듈테스트', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const v = await (await post('/api/pairing/verify', { code, name: 'Det-Farmer' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: v.slaveId, name: '바질', species })).json();
  return { userId: u.id, slaveId: v.slaveId, key: v.sessionKey, plantId: plant.plantId };
}

function collectMasterSse(slaveId) {
  const events = [];
  const req = http.get(`${base}/api/commands/sse/master/${slaveId}`, (res) => {
    res.setEncoding('utf8');
    let buf = '';
    res.on('data', (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const line = buf.slice(0, idx).split('\n').find((l) => l.startsWith('data: '));
        buf = buf.slice(idx + 2);
        if (line) try { events.push(JSON.parse(line.slice(6))); } catch { /* ignore */ }
      }
    });
  });
  req.on('error', () => {});
  return { events, close: () => req.destroy() };
}

const sha = (buf) => createHash('sha256').update(buf).digest('hex');

test('카탈로그: 3개 모듈 + 모델 sha256/크기가 실제 파일과 일치', async () => {
  const res = await get('/api/detectors');
  assert.equal(res.status, 200);
  const { modules, defaults } = await res.json();
  assert.deepEqual(modules.map((m) => m.id), ['health_grade', 'pest', 'watering']);
  assert.deepEqual(defaults, ['health_grade', 'pest', 'watering']);
  for (const m of modules) assert.equal(m.available, true, `${m.id} 모델이 있어야 함`);

  const hg = modules.find((m) => m.id === 'health_grade');
  const file = fs.readFileSync(path.join(MODELS_DIR, 'health_grade.bin'));
  assert.equal(hg.model.sha256, sha(file));
  assert.equal(hg.model.size, file.length);
  assert.equal(hg.model.url, '/api/detectors/models/health_grade.bin');
  assert.deepEqual(hg.labels, ['very_healthy', 'healthy', 'fair', 'poor', 'very_poor']);

  const w = modules.find((m) => m.id === 'watering');
  assert.equal(w.perSpecies, true);
  assert.equal(w.model, null);
  assert.ok(w.speciesModels.basil, '종별 모델(basil)이 있어야 함');
  assert.deepEqual(w.speciesOutputs, ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat']);
});

test('모델 다운로드: 바이트·ETag 일치, If-None-Match → 304, 카탈로그 밖 파일은 404', async () => {
  const { modules } = await (await get('/api/detectors')).json();
  const pest = modules.find((m) => m.id === 'pest').model;
  const res = await get(pest.url);
  assert.equal(res.status, 200);
  const buf = Buffer.from(await res.arrayBuffer());
  assert.equal(buf.length, pest.size);
  assert.equal(sha(buf), pest.sha256);
  assert.equal(res.headers.get('x-model-sha256'), pest.sha256);
  assert.equal(buf.subarray(0, 4).toString(), 'OJP1');

  const again = await get(pest.url, { 'If-None-Match': `"${pest.sha256}"` });
  assert.equal(again.status, 304);

  const basil = modules.find((m) => m.id === 'watering').speciesModels.basil;
  assert.equal((await get(basil.url)).status, 200);

  for (const bad of ['nope.bin', '..%2Fsrc%2Fdb.js', 'labels.json']) {
    assert.equal((await get(`/api/detectors/models/${bad}`)).status, 404, bad);
  }
});

test('신규 식물: 기본 모듈 3종이 켜져 있고 farmer 식물 목록에 detectors 로 노출', async () => {
  const { slaveId, plantId } = await pairedPlant();
  const { plants } = await (await get(`/api/plants/slave/${slaveId}`)).json();
  assert.deepEqual(plants[0].detectors, ['health_grade', 'pest', 'watering']);
  assert.equal(plants[0].detectors_json, undefined, '내부 컬럼은 노출하지 않음');

  const detail = await (await get(`/api/detectors/plant/${plantId}`)).json();
  assert.equal(detail.slaveId, slaveId);
  assert.deepEqual(detail.modules.map((m) => [m.id, m.enabled]), [['health_grade', true], ['pest', true], ['watering', true]]);
  assert.equal(detail.modules[0].latest, null);
  assert.ok(detail.modules[0].modelSize > 0);
});

test('삭제/추가 → detectors 반영 + detectors_sync 명령은 대기 중 1건만', async () => {
  const { slaveId, key, plantId } = await pairedPlant();

  const r1 = await del(`/api/detectors/plant/${plantId}/pest`);
  assert.equal(r1.status, 200);
  const b1 = await r1.json();
  assert.ok(b1.commandId);
  assert.equal(b1.modules.find((m) => m.id === 'pest').enabled, false);

  const r2 = await del(`/api/detectors/plant/${plantId}/watering`);
  const b2 = await r2.json();
  assert.equal(b2.commandId, b1.commandId, '대기 중인 동기화 명령을 재사용');

  let { plants } = await (await get(`/api/plants/slave/${slaveId}`)).json();
  assert.deepEqual(plants[0].detectors, ['health_grade']);

  const pending = await (await get(`/api/commands/pending/${slaveId}`, { 'x-session-key': key })).json();
  assert.equal(pending.commands.filter((c) => c.action === 'detectors_sync').length, 1);

  // farmer 가 처리 완료 → 다음 변경은 새 명령
  await post(`/api/commands/${b1.commandId}/done`, {});
  const r3 = await post(`/api/detectors/plant/${plantId}/pest`, {});
  assert.equal(r3.status, 200);
  const b3 = await r3.json();
  assert.notEqual(b3.commandId, b1.commandId);
  const pestRow = b3.modules.find((m) => m.id === 'pest');
  assert.equal(pestRow.enabled, true);
  assert.match(pestRow.addedAt, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);

  ({ plants } = await (await get(`/api/plants/slave/${slaveId}`)).json());
  assert.deepEqual(plants[0].detectors, ['health_grade', 'pest']);

  // 같은 모듈 재추가는 멱등 (addedAt 유지)
  const b4 = await (await post(`/api/detectors/plant/${plantId}/pest`, {})).json();
  assert.equal(b4.modules.find((m) => m.id === 'pest').addedAt, pestRow.addedAt);

  assert.equal((await post(`/api/detectors/plant/${plantId}/laser_eyes`, {})).status, 400);
  assert.equal((await post('/api/detectors/plant/none/pest', {})).status, 404);
});

test('결과 보고 → 최신 결과 조회 + master SSE push', async () => {
  const { slaveId, key, plantId } = await pairedPlant();
  const sse = collectMasterSse(slaveId);
  await new Promise((r) => setTimeout(r, 150));

  const res = await post('/api/detectors/results', {
    slaveId, plantId,
    results: [
      { moduleId: 'health_grade', label: 'poor', confidence: 0.82, trigger: 'periodic', modelSha: 'abc' },
      { moduleId: 'watering', label: 'dry', confidence: 0.7, trigger: 'watered', detail: { hoursSinceWater: 30.5, avgDryHours: 28 } },
    ],
  }, { 'x-session-key': key });
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { accepted: 2, skipped: 0 });

  await new Promise((r) => setTimeout(r, 300));
  sse.close();
  const pushed = sse.events.find((e) => e.type === 'detector_result');
  assert.ok(pushed, 'detector_result 가 master SSE 로 push 되어야 함');
  assert.equal(pushed.plantId, plantId);
  assert.equal(pushed.results.length, 2);

  const detail = await (await get(`/api/detectors/plant/${plantId}`)).json();
  const hg = detail.modules.find((m) => m.id === 'health_grade');
  assert.equal(hg.latest.label, 'poor');
  assert.equal(hg.latest.confidence, 0.82);
  const w = detail.modules.find((m) => m.id === 'watering');
  assert.equal(w.latest.trigger, 'watered');
  assert.deepEqual(w.latest.detail, { hoursSinceWater: 30.5, avgDryHours: 28 });
  assert.equal(detail.modules.find((m) => m.id === 'pest').latest, null);

  const hist = await (await get(`/api/detectors/plant/${plantId}/history?module=health_grade`)).json();
  assert.equal(hist.results.length, 1);
});

test('결과 보고 검증: 세션키·식물 소유·라벨·꺼진 모듈', async () => {
  const a = await pairedPlant();
  const b = await pairedPlant();
  const body = { slaveId: a.slaveId, plantId: a.plantId, results: [{ moduleId: 'pest', label: 'light', confidence: 0.6 }] };

  assert.equal((await post('/api/detectors/results', body)).status, 401);
  assert.equal((await post('/api/detectors/results', body, { 'x-session-key': b.key })).status, 403);
  // 다른 farmer 의 식물에 쓰기 금지
  const foreign = await post('/api/detectors/results', { ...body, plantId: b.plantId }, { 'x-session-key': a.key });
  assert.equal(foreign.status, 403);

  const badLabel = { ...body, results: [{ moduleId: 'pest', label: 'tons' }] };
  assert.equal((await post('/api/detectors/results', badLabel, { 'x-session-key': a.key })).status, 400);
  const badConf = { ...body, results: [{ moduleId: 'pest', label: 'light', confidence: 7 }] };
  assert.equal((await post('/api/detectors/results', badConf, { 'x-session-key': a.key })).status, 400);

  // 삭제 직후 도착한 결과는 버린다
  await del(`/api/detectors/plant/${a.plantId}/pest`);
  const late = await post('/api/detectors/results', body, { 'x-session-key': a.key });
  assert.deepEqual(await late.json(), { accepted: 0, skipped: 1 });
});

test('모델 준비 상태 보고 → 켜진 모듈에만 표시', async () => {
  const { slaveId, key, plantId } = await pairedPlant('몬스테라');
  const res = await post('/api/detectors/state', {
    slaveId,
    states: [
      { plantId, moduleId: 'health_grade', state: 'ready', modelSha: 'f00' },
      { plantId, moduleId: 'watering', state: 'unsupported', detail: 'no species model — heuristic' },
    ],
  }, { 'x-session-key': key });
  assert.deepEqual(await res.json(), { accepted: 2 });

  let detail = await (await get(`/api/detectors/plant/${plantId}`)).json();
  assert.equal(detail.modules.find((m) => m.id === 'health_grade').state.state, 'ready');
  assert.equal(detail.modules.find((m) => m.id === 'watering').state.detail, 'no species model — heuristic');

  await del(`/api/detectors/plant/${plantId}/watering`);
  detail = await (await get(`/api/detectors/plant/${plantId}`)).json();
  assert.equal(detail.modules.find((m) => m.id === 'watering').state, null);

  const bad = await post('/api/detectors/state', { slaveId, states: [{ plantId, moduleId: 'pest', state: 'happy' }] }, { 'x-session-key': key });
  assert.equal(bad.status, 400);
});

test('관수 주기: 서버 관수 기록으로 마지막 관수·간격 수 계산', async () => {
  const { slaveId, key, plantId } = await pairedPlant();
  let w = (await (await get(`/api/detectors/plant/${plantId}`)).json()).modules.find((m) => m.id === 'watering');
  assert.deepEqual(w.cycle, { lastWateredAt: null, avgIntervalHours: null, intervals: 0 });

  for (let i = 0; i < 3; i++) {
    const lr = await post('/api/watering/log', { slaveId, plantId, amountMl: 200, source: 'auto' }, { 'x-session-key': key });
    assert.equal(lr.status, 200);
  }
  w = (await (await get(`/api/detectors/plant/${plantId}`)).json()).modules.find((m) => m.id === 'watering');
  assert.ok(w.cycle.lastWateredAt);
  assert.equal(w.cycle.intervals, 2);
  assert.equal(typeof w.cycle.avgIntervalHours, 'number');
});

test('master 즉시 판독 명령(run_detectors) 허용', async () => {
  const { slaveId, plantId } = await pairedPlant();
  const res = await post('/api/commands', { slaveId, plantId, action: 'run_detectors' });
  assert.equal(res.status, 200);
});

test('RoI·배정 변경 → farmer 재동기화 명령 (변경 없으면 없음)', async () => {
  const { slaveId, key, plantId } = await pairedPlant();
  const pending = async () => (await (await get(`/api/commands/pending/${slaveId}`, { 'x-session-key': key })).json())
    .commands.filter((c) => c.action === 'detectors_sync');
  // 식물 등록 시 1건
  const first = await pending();
  assert.equal(first.length, 1);
  await post(`/api/commands/${first[0].id}/done`, {});

  const put = (body) => fetch(`${base}/api/plants/plant/${plantId}`, { method: 'PUT', headers: json, body: JSON.stringify(body) });
  await put({ name: '바질2' });
  assert.equal((await pending()).length, 0, '이름만 바꾸면 동기화 불필요');

  await put({ roi: { x: 0.1, y: 0.1, w: 0.3, h: 0.3 } });
  assert.equal((await pending()).length, 1);
});

test('서버 배포 모델 = 앱 내장 모델 (재학습 후 sync-detector-models 누락 방지)', () => {
  const assets = path.resolve(MODELS_DIR, '../../android/slave/app/src/main/assets/models');
  if (!fs.existsSync(assets)) return; // 백엔드만 배포된 환경
  for (const name of fs.readdirSync(assets).filter((n) => n.endsWith('.bin'))) {
    const a = sha(fs.readFileSync(path.join(assets, name)));
    const b = sha(fs.readFileSync(path.join(MODELS_DIR, name)));
    assert.equal(b, a, `${name} 불일치 — node scripts/sync-detector-models.mjs 실행`);
  }
});
