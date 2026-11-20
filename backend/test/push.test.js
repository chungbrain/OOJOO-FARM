// FCM 푸시 서비스 통합 테스트 — 설정 게이트 / JWT / 메시지 형식 / 이벤트 훅.
// 실제 네트워크 없음: node:crypto 로 RSA 키쌍 생성 + fetch 스텁.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-push-test-${process.pid}.db`);
process.env.DB_PATH = tmpDb;

// 서비스 계정 (테스트 전용 RSA 키쌍) — env 로 push.js 가 읽는다.
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const SA = {
  project_id: 'test-project',
  client_email: 'test-sa@test-project.iam.gserviceaccount.com',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
};
process.env.FCM_PROJECT_ID = 'test-project';
process.env.FCM_SERVICE_ACCOUNT = JSON.stringify(SA);

let server;
let base;
let app;
let push;
let pushEnabled;

// fetch 스텁 — 호출 로그. OAuth/FCM 두 엔드포인트를 응답으로 구분.
let fetchCalls = [];
let fetchShouldThrow = false;

before(async () => {
  ({ default: app } = await import('../src/app.js'));
  push = await import('../src/lib/push.js');
  pushEnabled = push.pushEnabled;
  push._test.setFetch(async (url, opts) => {
    fetchCalls.push({ url: String(url), opts });
    if (fetchShouldThrow) throw new Error('stub network down');
    if (String(url).includes('oauth2.googleapis.com')) {
      return { ok: true, json: async () => ({ access_token: 'at-123', expires_in: 3600 }) };
    }
    return { ok: true, json: async () => ({ name: 'projects/test-project/messages/msg1' }) };
  });
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
const del = (p, body, headers = {}) =>
  fetch(base + p, { method: 'DELETE', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });

async function makeUserWithSlave(nickname) {
  const u = await (await post('/api/users', { nickname, region: 'Busan' })).json();
  const code = await (await post('/api/pairing/code', { userId: u.id })).json();
  const v = await (await post('/api/pairing/verify', { code: code.code })).json();
  return { user: u, slaveId: v.slaveId, sessionKey: v.sessionKey };
}

test('push-token 등록: 추가/중복 제거/삭제/검증', async () => {
  const { user } = await makeUserWithSlave('pusher');
  const t1 = 'token-'.padEnd(24, '1');
  const r1 = await (await post(`/api/users/${user.id}/push-token`, { token: t1 })).json();
  assert.equal(r1.count, 1);

  // 중복 등록 — 카운트 유지
  const r2 = await (await post(`/api/users/${user.id}/push-token`, { token: t1 })).json();
  assert.equal(r2.count, 1);

  const t2 = 'token-'.padEnd(24, '2');
  const r3 = await (await post(`/api/users/${user.id}/push-token`, { token: t2 })).json();
  assert.equal(r3.count, 2);

  // 삭제
  const r4 = await (await del(`/api/users/${user.id}/push-token`, { token: t1 })).json();
  assert.equal(r4.count, 1);

  // 검증
  assert.equal((await post(`/api/users/${user.id}/push-token`, { token: 'short' })).status, 400);
  assert.equal((await post(`/api/users/${user.id}/push-token`, {})).status, 400);
  assert.equal((await post('/api/users/nonexistent000/push-token', { token: t1 })).status, 404);
});

test('FCM 무설정(no-op): 미설정 환경이면 이벤트에도 네트워크 호출 없음', async () => {
  const savedProj = process.env.FCM_PROJECT_ID;
  const savedSa = process.env.FCM_SERVICE_ACCOUNT;
  delete process.env.FCM_PROJECT_ID;
  delete process.env.FCM_SERVICE_ACCOUNT;
  try {
    assert.equal(pushEnabled(), false);
    fetchCalls = [];
    const { user, slaveId, sessionKey } = await makeUserWithSlave('nopusher');
    await post(`/api/users/${user.id}/push-token`, { token: 'token-'.padEnd(24, '9') });
    const r = await post('/api/events', { slaveId, type: 'harvest_ready', payload: { ripeness: '0.9' } }, { 'x-session-key': sessionKey });
    assert.equal(r.status, 200);
    await push._test.flush();
    assert.equal(fetchCalls.length, 0, 'FCM 미설정이라 OAuth/FCM 호출이 없어야 한다');
  } finally {
    process.env.FCM_PROJECT_ID = savedProj;
    process.env.FCM_SERVICE_ACCOUNT = savedSa;
  }
});

test('FCM 푸시: health_change → JWT 서명 검증 + 토큰/데이터 형식', async () => {
  fetchCalls = [];
  const { user, slaveId, sessionKey } = await makeUserWithSlave('fcmev');
  const t1 = 'fcm-token-'.padEnd(26, 'a');
  await post(`/api/users/${user.id}/push-token`, { token: t1 });

  const r = await post('/api/events', { slaveId, plantId: 'plant1', type: 'health_change', payload: { from: 'good', to: 'poor' } }, { 'x-session-key': sessionKey });
  assert.equal(r.status, 200);
  await push._test.flush();

  // 1) OAuth 토큰 발급, 2) FCM 메시지 전송
  assert.equal(fetchCalls.length, 2, `OAuth+FCM 1회씩 호출 (실제 ${fetchCalls.length})`);
  const [oauthCall, fcmCall] = fetchCalls;
  assert.ok(oauthCall.url.includes('oauth2.googleapis.com/token'));

  // JWT 검증: header/claims 디코딩 + RSA 서명 검증
  const assertion = new URLSearchParams(oauthCall.opts.body).get('assertion');
  const [h, c, sig] = assertion.split('.');
  const header = JSON.parse(Buffer.from(h.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
  const claims = JSON.parse(Buffer.from(c.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
  assert.equal(header.alg, 'RS256');
  assert.equal(claims.iss, SA.client_email);
  assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
  assert.ok(claims.exp > claims.iat);
  const verified = crypto.createVerify('RSA-SHA256').update(`${h}.${c}`).verify(publicKey, Buffer.from(sig.replace(/-/g, '+').replace(/_/g, '/'), 'base64'));
  assert.ok(verified, 'JWT 서명이 서비스 계정 키로 검증되어야 한다');

  // FCM 메시지: Bearer 토큰 + tokens + data
  assert.ok(fcmCall.url.includes('v1/projects/test-project/messages:send'));
  assert.equal(fcmCall.opts.headers.Authorization, 'Bearer at-123');
  const msg = JSON.parse(fcmCall.opts.body);
  assert.deepEqual(msg.message.tokens, [t1]);
  assert.equal(msg.message.data.type, 'health_change');
  assert.equal(msg.message.data.slaveId, slaveId);
  assert.equal(msg.message.data.plantId, 'plant1');
  assert.ok(JSON.parse(msg.message.data.payload).from === 'good');
});

test('FCM 푸시 대상 구분: water_failed/battery_low 포함, anomaly 는 제외', async () => {
  fetchCalls = [];
  const { user, slaveId, sessionKey } = await makeUserWithSlave('fcmscope');
  await post(`/api/users/${user.id}/push-token`, { token: 'fcm-token-'.padEnd(26, 'b') });

  await post('/api/events', { slaveId, type: 'water_failed', payload: { reason: 'hardware_offline' } }, { 'x-session-key': sessionKey });
  await push._test.flush();
  assert.equal(fetchCalls.length, 1, 'water_failed → FCM 전송');

  await post('/api/events', { slaveId, type: 'battery_low', payload: { level: '15' } }, { 'x-session-key': sessionKey });
  await push._test.flush();
  assert.equal(fetchCalls.length, 2, 'battery_low → FCM 전송');

  await post('/api/events', { slaveId, type: 'anomaly', payload: {} }, { 'x-session-key': sessionKey });
  await push._test.flush();
  assert.equal(fetchCalls.length, 2, 'anomaly 는 FCM 대상 아님');
});

test('FCM 가동중 전송 실패도 이벤트 API 에는 영향 없음 (fire-and-forget)', async () => {
  fetchCalls = [];
  fetchShouldThrow = true;
  try {
    const { user, slaveId, sessionKey } = await makeUserWithSlave('fcmdown');
    await post(`/api/users/${user.id}/push-token`, { token: 'fcm-token-'.padEnd(26, 'c') });
    const r = await post('/api/events', { slaveId, type: 'pest_detected', payload: {} }, { 'x-session-key': sessionKey });
    assert.equal(r.status, 200, 'FCM 실패에도 이벤트 저장은 정상');
    await push._test.flush();
  } finally {
    fetchShouldThrow = false;
  }
});

test('FCM 가구 공유: 가구원 디바이스도 수신', async () => {
  // 가구 만들기: owner + member 계정 → 초대 → 수락
  const owner = await (await post('/api/users', { nickname: 'owner', region: 'Busan' })).json();
  const member = await (await post('/api/users', { nickname: 'member', region: 'Daegu' })).json();
  const invite = await (await post('/api/household/invite', { userId: owner.id })).json();
  assert.ok(invite.code, '초대 코드 생성');
  const accept = await post('/api/household/accept', { userId: member.id, code: invite.code });
  assert.equal(accept.status, 200, '멤버 수락');

  const code2 = await (await post('/api/pairing/code', { userId: owner.id })).json();
  const v = await (await post('/api/pairing/verify', { code: code2.code })).json();
  const { slaveId, sessionKey } = { slaveId: v.slaveId, sessionKey: v.sessionKey };

  const tOwner = 'fcm-token-'.padEnd(26, 'd');
  const tMember = 'fcm-token-'.padEnd(26, 'e');
  await post(`/api/users/${owner.id}/push-token`, { token: tOwner });
  await post(`/api/users/${member.id}/push-token`, { token: tMember });

  fetchCalls = [];
  await post('/api/events', { slaveId, type: 'harvest_ready', payload: { ripeness: '0.8' } }, { 'x-session-key': sessionKey });
  await push._test.flush();

  const fcmSends = fetchCalls.filter((c) => c.url.includes('messages:send'));
  const sentTokens = fcmSends.map((c) => JSON.parse(c.opts.body).message.tokens[0]);
  assert.ok(sentTokens.includes(tOwner), 'owner 수신');
  assert.ok(sentTokens.includes(tMember), '가구원 수신');
});
