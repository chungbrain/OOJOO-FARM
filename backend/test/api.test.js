// OOJOO FARM 백엔드 통합 테스트 (node:test, 외부 의존성 없음)
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { computeWateringFactor } from '../src/lib/wateringFactor.js';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-test-${process.pid}.db`);
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
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

const post = (p, body, headers = {}) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
const put = (p, body) =>
  fetch(base + p, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p, headers = {}) => fetch(base + p, { headers });

test('computeWateringFactor: 고온이면 증가, 저온/고습/강수면 감소', () => {
  assert.ok(computeWateringFactor(32, 40, 0) > 1.0);
  assert.ok(computeWateringFactor(10, 40, 0) < 1.0);
  assert.ok(computeWateringFactor(22, 80, 0) < 1.0);   // 고습 ×0.7
  assert.ok(computeWateringFactor(22, 40, 5) < 0.5);   // 강수 ×0.3
  assert.equal(computeWateringFactor(22, 40, 0), 1.0); // 기준
});

test('health 체크', async () => {
  const r = await get('/health');
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.ok, true);
});

test('알 수 없는 경로는 404', async () => {
  const r = await get('/api/nope');
  assert.equal(r.status, 404);
});

test('계정 생성/조회', async () => {
  const r = await post('/api/users', { nickname: '홍길동', region: 'Seoul' });
  assert.equal(r.status, 200);
  const u = await r.json();
  assert.ok(u.id);
  assert.equal(u.region, 'Seoul');

  const r2 = await get(`/api/users/${u.id}`);
  assert.equal(r2.status, 200);
});

test('페어링 → 세션키 인증 흐름', async () => {
  // 계정
  const u = await (await post('/api/users', { nickname: 'farmer', region: 'Busan' })).json();

  // 코드 생성
  const codeResp = await (await post('/api/pairing/code', { userId: u.id })).json();
  assert.ok(codeResp.code);

  // 슬레이브 검증
  const verify = await (await post('/api/pairing/verify', { code: codeResp.code })).json();
  assert.ok(verify.slaveId);
  assert.ok(verify.sessionKey);
  assert.equal(verify.userId, u.id);

  // 같은 코드 재사용 불가
  const reuse = await post('/api/pairing/verify', { code: codeResp.code });
  assert.equal(reuse.status, 410);

  // 하트비트: 세션키 없으면 401
  const noKey = await post('/api/pairing/heartbeat', { slaveId: verify.slaveId });
  assert.equal(noKey.status, 401);

  // 하트비트: 잘못된 키 403
  const badKey = await post('/api/pairing/heartbeat', { slaveId: verify.slaveId }, { 'x-session-key': 'wrong' });
  assert.equal(badKey.status, 403);

  // 하트비트: 올바른 키 200
  const okKey = await post('/api/pairing/heartbeat', { slaveId: verify.slaveId, battery: 88 }, { 'x-session-key': verify.sessionKey });
  assert.equal(okKey.status, 200);

  // 슬레이브 목록에 배터리 반영
  const slaves = await (await get(`/api/pairing/${u.id}`)).json();
  assert.equal(slaves.slaves.length, 1);
  assert.equal(slaves.slaves[0].battery, 88);
});

test('자율 정책 기본값 + 갱신', async () => {
  const u = await (await post('/api/users', { region: 'Daegu' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();

  // 기본 정책
  const def = await (await get(`/api/policy/${verify.slaveId}`)).json();
  assert.equal(def.water_auto, 1);
  assert.equal(def.capture_interval, 60);

  // 갱신
  const upd = await (await put(`/api/policy/${verify.slaveId}`, { waterAuto: false, captureInterval: 15, region: 'Daegu' })).json();
  assert.equal(upd.water_auto, 0);
  assert.equal(upd.capture_interval, 15);

  const after = await (await get(`/api/policy/${verify.slaveId}`)).json();
  assert.equal(after.water_auto, 0);
  assert.equal(after.region, 'Daegu');
});

test('명령 큐: 세션키 인증 + 폴링/완료', async () => {
  const u = await (await post('/api/users', { region: 'Incheon' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const key = { 'x-session-key': verify.sessionKey };

  // 마스터가 관수 명령 등록
  const cmd = await (await post('/api/commands', { slaveId: verify.slaveId, action: 'water', amountMl: 250 })).json();
  assert.ok(cmd.commandId);

  // 슬레이브 폴링: 키 없으면 401
  assert.equal((await get(`/api/commands/pending/${verify.slaveId}`)).status, 401);

  // 키 있으면 명령 조회
  const pending = await (await get(`/api/commands/pending/${verify.slaveId}`, key)).json();
  assert.equal(pending.commands.length, 1);
  assert.equal(pending.commands[0].amount_ml, 250);

  // 완료 보고
  const done = await post(`/api/commands/${cmd.commandId}/done`, {});
  assert.equal(done.status, 200);

  const afterDone = await (await get(`/api/commands/pending/${verify.slaveId}`, key)).json();
  assert.equal(afterDone.commands.length, 0);
});

test('알림 센터: 사용자 슬레이브 이벤트 집계', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const key = { 'x-session-key': verify.sessionKey };

  await post('/api/events', { slaveId: verify.slaveId, type: 'harvest_ready', payload: { count: 3 } }, key);
  await post('/api/events', { slaveId: verify.slaveId, type: 'pest_detected', payload: {} }, key);

  const notis = await (await get(`/api/notifications/${u.id}`)).json();
  assert.ok(notis.notifications.length >= 2);
  assert.ok(notis.notifications.some((n) => n.type === 'harvest_ready'));
  assert.ok(notis.notifications[0].slave_name);
});
