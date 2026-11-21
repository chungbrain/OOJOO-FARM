// 실시간 보기 릴레이 (/api/live) 통합 테스트 (node:test, 외부 의존성 없음)
//  - live_start/live_stop 명령 등록 검증 (COMMAND_ACTIONS)
//  - slave 프레임 push → master 3초 폴링 pull (최신 1장 유지)
//  - 세션키 인증 (미인증 push 거부)
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-live-test-${process.pid}.db`);
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
  try { server?.closeAllConnections?.(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

const post = (p, body, headers = {}) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
const get = (p) => fetch(base + p);

async function setupPairedSlave() {
  const u = await (await post('/api/users', { nickname: '라이브테스트', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code, name: 'Live-Farmer' })).json();
  return { slaveId: verify.slaveId, sessionKey: verify.sessionKey };
}

/** multipart 프레임 push — Node 내장 FormData/Blob. */
function pushFrame(slaveId, sessionKey, bytes, field = 'frame') {
  const form = new FormData();
  form.append(field, new Blob([bytes], { type: 'image/jpeg' }), 'live.jpg');
  return fetch(`${base}/api/live/${slaveId}/frame`, {
    method: 'POST',
    headers: { 'x-session-key': sessionKey },
    body: form,
  });
}

test('live_start/live_stop 명령이 /api/commands 에 등록된다', async () => {
  const { slaveId, sessionKey } = await setupPairedSlave();
  for (const action of ['live_start', 'live_stop']) {
    const res = await post('/api/commands', { slaveId, action });
    assert.equal(res.status, 200);
    const j = await res.json();
    assert.ok(j.commandId, `${action} 명령 ID 발급`);
  }
  // 큐에 쌓인 두 명령을 slave 폴링으로 회수 (검증: pending 집합에 포함 — 세션키 필요)
  const pendingRes = await fetch(`${base}/api/commands/pending/${slaveId}`, {
    headers: { 'x-session-key': sessionKey },
  });
  assert.equal(pendingRes.status, 200);
  const pending = await pendingRes.json();
  const actions = pending.commands.map((c) => c.action);
  assert.ok(actions.includes('live_start'));
  assert.ok(actions.includes('live_stop'));
});

test('slave 프레임 push → master pull: 최신 1장이 image/jpeg 로 응답', async () => {
  const { slaveId, sessionKey } = await setupPairedSlave();

  // 프레임 없는 상태 — 404 (master 가 "신호 없음"을 감지하는 경로)
  const none = await get(`/api/live/${slaveId}/frame`);
  assert.equal(none.status, 404);
  const st0 = await (await get(`/api/live/${slaveId}/status`)).json();
  assert.equal(st0.active, false);
  assert.equal(st0.lastFrameAt, null);

  // 첫 프레임 push
  const frame1 = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0x01]);
  const up1 = await pushFrame(slaveId, sessionKey, frame1);
  assert.equal(up1.status, 200);

  const got1 = await get(`/api/live/${slaveId}/frame`);
  assert.equal(got1.status, 200);
  assert.equal(got1.headers.get('content-type'), 'image/jpeg');
  assert.ok(got1.headers.get('x-frame-at'), '프레임 수신 시각 헤더');
  assert.deepEqual(Buffer.from(await got1.arrayBuffer()), frame1);

  // 두 번째 프레임 — 최신 1장만 유지 (3초 라이브: 항상 마지막 프레임)
  const frame2 = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0x02]);
  await pushFrame(slaveId, sessionKey, frame2);
  const got2 = await get(`/api/live/${slaveId}/frame`);
  assert.deepEqual(Buffer.from(await got2.arrayBuffer()), frame2);

  const st1 = await (await get(`/api/live/${slaveId}/status`)).json();
  assert.equal(st1.active, true);
  assert.ok(st1.lastFrameAt > 0);
});

test('프레임 push 는 세션키 인증이 필요하다', async () => {
  const { slaveId } = await setupPairedSlave();
  const res = await pushFrame(slaveId, 'wrong-key', Buffer.from([0xFF, 0xD8]));
  assert.equal(res.status, 403);
});

test('프레임 파일 없는 push 는 400', async () => {
  const { slaveId, sessionKey } = await setupPairedSlave();
  const form = new FormData();
  form.append('not_frame', new Blob([Buffer.from([1])], { type: 'image/jpeg' }), 'x.jpg');
  const res = await fetch(`${base}/api/live/${slaveId}/frame`, {
    method: 'POST',
    headers: { 'x-session-key': sessionKey },
    body: form,
  });
  assert.equal(res.status, 400);
});
