// F3/F4 — farmer 신규 기능 통합 테스트 (node:test, 외부 의존성 없음)
//  - health_change 이벤트 → master SSE 실시간 push + 알림 센터 집계
//  - camera_mount 정책 (auto/landscape/portrait) 원격 설정
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-farmer-test-${process.pid}.db`);
process.env.DB_PATH = tmpDb;

let server;
let base;

before(async () => {
  const { default: app } = await import('../src/app.js');
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  // SSE 연결 등 남은 소켓을 강제 정리 — 테스트 프로세스 종료 보장
  try { server?.closeAllConnections?.(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

const post = (p, body, headers = {}) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
const put = (p, body) =>
  fetch(base + p, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p) => fetch(base + p);

async function setupPairedPlant() {
  const u = await (await post('/api/users', { nickname: '파머테스트', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code, name: 'Sim-Farmer' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name: '바질', species: 'basil' })).json();
  return { userId: u.id, slaveId: verify.slaveId, sessionKey: verify.sessionKey, plantId: plant.plantId };
}

/** master SSE 스트림을 열고 수신된 이벤트를 모은다.
 * node:http + req.destroy() — fetch/AbortController 는 소켓 종료가
 * 비동기적으로 지연되어 테스트 프로세스가 행업되는 문제가 있어 사용하지 않는다. */
function collectMasterSse(slaveId) {
  const events = [];
  const req = http.get(`${base}/api/commands/sse/master/${slaveId}`, (res) => {
    res.setEncoding('utf8');
    let buf = '';
    res.on('data', (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const line = block.split('\n').find((l) => l.startsWith('data: '));
        if (!line) continue; // ': heartbeat' keep-alive 등 무시
        try { events.push(JSON.parse(line.slice(6))); } catch { /* 파싱 실패 무시 */ }
      }
    });
  });
  req.on('error', () => {}); // close 시 ECONNRESET 무시
  return { events, close: () => req.destroy() };
}

test('F3: health_change 이벤트 → master SSE push + 알림 센터 집계', async () => {
  const { userId, slaveId, sessionKey, plantId } = await setupPairedPlant();
  const sse = collectMasterSse(slaveId);
  await new Promise((r) => setTimeout(r, 150)); // SSE 연결 안정화

  const res = await post('/api/events', {
    slaveId, plantId, type: 'health_change',
    payload: {
      from: 'healthy', to: 'poor', fromKo: '건강', toKo: '나쁨',
      confidence: 0.91, modelId: 'health_grade',
      pestFrom: 'none', pestTo: 'severe', pestLevelKo: '심각발생', pestConfidence: 0.88, pestModelId: 'pest',
    },
  }, { 'x-session-key': sessionKey });
  assert.equal(res.status, 200);
  const { eventId } = await res.json();
  assert.ok(eventId);

  // master SSE 로 실시간 push 확인
  await new Promise((r) => setTimeout(r, 400));
  sse.close();
  const pushed = sse.events.find((e) => e.type === 'health_change');
  assert.ok(pushed, 'health_change 가 master SSE 로 push 되어야 함');
  assert.equal(pushed.eventId, eventId);
  assert.equal(pushed.slaveId, slaveId);
  assert.equal(pushed.plantId, plantId);
  assert.equal(pushed.payload.to, 'poor');

  // 알림 센터(폴링) 집계 확인 — 식물명 조인 포함
  const notis = await (await get(`/api/notifications/${userId}`)).json();
  const noti = notis.notifications.find((n) => n.type === 'health_change');
  assert.ok(noti, '알림 센터에 health_change 가 집계되어야 함');
  assert.equal(noti.plant_name, '바질');
  const payload = JSON.parse(noti.payload);
  assert.equal(payload.toKo, '나쁨');
  assert.equal(payload.pestTo, 'severe');
});

test('F3: noti 대상 외 이벤트는 SSE push 하지 않음 (알림 센터에만)', async () => {
  const { userId, slaveId, sessionKey, plantId } = await setupPairedPlant();
  const sse = collectMasterSse(slaveId);
  await new Promise((r) => setTimeout(r, 150));

  await post('/api/events', { slaveId, plantId, type: 'auto_water', payload: { amountMl: 200 } }, { 'x-session-key': sessionKey });
  await new Promise((r) => setTimeout(r, 300));
  sse.close();
  assert.ok(!sse.events.some((e) => e.type === 'auto_water'), 'auto_water 은 push 대상 아님');

  const notis = await (await get(`/api/notifications/${userId}`)).json();
  assert.ok(notis.notifications.some((n) => n.type === 'auto_water'), '알림 센터에는 기록');
});

test('F4: camera_mount 정책 — 기본 auto, 원격 설정/조회/검증', async () => {
  const { slaveId } = await setupPairedPlant();

  // 기본값
  const def = await (await get(`/api/policy/${slaveId}`)).json();
  assert.equal(def.camera_mount, 'auto');

  // landscape 설정
  const set1 = await (await put(`/api/policy/${slaveId}`, { cameraMount: 'landscape' })).json();
  assert.equal(set1.camera_mount, 'landscape');
  const got1 = await (await get(`/api/policy/${slaveId}`)).json();
  assert.equal(got1.camera_mount, 'landscape');

  // portrait 변경 + 다른 필드 보존
  const set2 = await (await put(`/api/policy/${slaveId}`, { cameraMount: 'portrait', roiInterval: 15 })).json();
  assert.equal(set2.camera_mount, 'portrait');
  assert.equal(set2.roi_interval, 15);
  const got2 = await (await get(`/api/policy/${slaveId}`)).json();
  assert.equal(got2.camera_mount, 'portrait');

  // 허용 외 값은 기각 (기존값 유지)
  const set3 = await (await put(`/api/policy/${slaveId}`, { cameraMount: 'diagonal' })).json();
  assert.equal(set3.camera_mount, 'portrait', '허용 밖 값은 무시되어야 함');
});
