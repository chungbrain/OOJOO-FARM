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

test('계정 응답 PII: password_hash 안 새고, 비인증 조회에 email 없다', async () => {
  // register — 자기 이메일은 반환 (로그인/세션 저장용)
  const reg = await (await post('/api/users/register', { email: 'pii@test.com', password: 'secret123', nickname: 'pii', region: 'Seoul' })).json();
  assert.ok(reg.id);
  assert.equal(reg.email, 'pii@test.com');
  assert.equal(reg.password_hash, undefined);

  // 익명 업서 — 과거 SELECT * 가 password_hash 를 흘렸다
  const u = await (await post('/api/users', { nickname: 'nohash', region: 'Seoul' })).json();
  assert.equal(u.password_hash, undefined);
  assert.ok(!u.email);

  // 비인증 프로필 조회 — register된 사용자의 email 도 반환되지 않아야 한다
  const prof = await (await get(`/api/users/${reg.id}`)).json();
  assert.equal(prof.password_hash, undefined);
  assert.equal(prof.email, undefined);
  assert.ok(prof.id);
});

test('프로필 수정: 지역/관심작물 부분 업데이트 (PRD 4.9)', async () => {
  const reg = await (await post('/api/users/register', { email: 'profile@test.com', password: 'secret123', nickname: '프로', region: 'Seoul' })).json();

  // 전체 업데이트
  let r = await fetch(`${base}/api/users/${reg.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ region: 'Busan', interests: ['strawberry', 'basil'] }) });
  assert.equal(r.status, 200);
  let j = await r.json();
  assert.equal(j.region, 'Busan');
  assert.deepEqual(j.interests, ['strawberry', 'basil']);
  assert.equal(j.nickname, '프로'); // 준 필드만 변경

  // 부분 업데이트 — interests만
  r = await fetch(`${base}/api/users/${reg.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ interests: [] }) });
  j = await r.json();
  assert.deepEqual(j.interests, []);
  assert.equal(j.region, 'Busan'); // 유지

  // GET 으로도 반영 + email 여전히 없음
  const prof = await (await get(`/api/users/${reg.id}`)).json();
  assert.equal(prof.region, 'Busan');
  assert.equal(prof.email, undefined);

  // 검증 실패 (빈 배열 허용은 위에서 이미 검증됨)
  for (const interests of ['strawberry', ['a'.repeat(51)], Array.from({ length: 21 }, () => 'x'), ['ok', '']]) {
    r = await fetch(`${base}/api/users/${reg.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ interests }) });
    assert.equal(r.status, 400, `interests=${JSON.stringify(interests).slice(0, 40)}`);
  }
  // unknown id
  assert.equal((await fetch(`${base}/api/users/ghost-ghost-ghost`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ region: 'Seoul' }) })).status, 404);
});

test('익명 업서 응답: register된 id라도 email 안 나간다', async () => {
  const reg = await (await post('/api/users/register', { email: 'upsert@test.com', password: 'secret123' })).json();
  const u = await (await post('/api/users', { id: reg.id, nickname: '업서' })).json();
  assert.equal(u.email, undefined);
  assert.equal(u.password_hash, undefined);
  assert.equal(u.nickname, '업서');
});

test('슬레이브 이름 변경 (PRD 4.1 별명 부여)', async () => {
  const u = await (await post('/api/users', { nickname: 'owner', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();

  // 기본 이름으로 시작
  const before = await (await get(`/api/pairing/${u.id}`)).json();
  assert.match(before.slaves[0].name, /^Farmer-/);

  const r = await put(`/api/pairing/${verify.slaveId}`, { name: '베란다 파머' });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).name, '베란다 파머');

  const after = await (await get(`/api/pairing/${u.id}`)).json();
  assert.equal(after.slaves[0].name, '베란다 파머');

  // 검증
  assert.equal((await put(`/api/pairing/${verify.slaveId}`, { name: '  ' })).status, 400);
  assert.equal((await put(`/api/pairing/${verify.slaveId}`, { name: 'x'.repeat(51) })).status, 400);
  assert.equal((await put(`/api/pairing/ghost-ghost-ghost`, { name: 'a' })).status, 404);
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

  // PRD 4.2: 캡처 주기 하한 10분 (과거: 1분 허용)
  const clampLo = await (await put(`/api/policy/${verify.slaveId}`, { captureInterval: 5 })).json();
  assert.equal(clampLo.capture_interval, 10);
  const clampHi = await (await put(`/api/policy/${verify.slaveId}`, { captureInterval: 9999 })).json();
  assert.equal(clampHi.capture_interval, 360);

  // PRD 4.2: 해충 짧은 주기 기본 15분, 범위 5~120분
  const defPest = await (await get(`/api/policy/${verify.slaveId}`)).json();
  assert.equal(defPest.pest_interval, 15);
  const pestLo = await (await put(`/api/policy/${verify.slaveId}`, { pestInterval: 1 })).json();
  assert.equal(pestLo.pest_interval, 5);
  const pestHi = await (await put(`/api/policy/${verify.slaveId}`, { pestInterval: 9999 })).json();
  assert.equal(pestHi.pest_interval, 120);
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

test('명령 취소: queued 만 취소 가능, 취소 이후 폴링에 안 남는다', async () => {
  const u = await (await post('/api/users', { region: 'Incheon' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const key = { 'x-session-key': verify.sessionKey };

  const cmd = await (await post('/api/commands', { slaveId: verify.slaveId, action: 'water', amountMl: 100 })).json();
  assert.ok(cmd.commandId);

  // queued → 취소 성공
  assert.equal((await post(`/api/commands/${cmd.commandId}/cancel`, {})).status, 200);
  // 이미 취소됨 → 409
  assert.equal((await post(`/api/commands/${cmd.commandId}/cancel`, {})).status, 409);
  // 존재하지 않는 id → 404
  assert.equal((await post('/api/commands/nope-nope-nope/cancel', {})).status, 404);

  // 폴링에 취소된 명령은 안 보인다
  const pending = await (await get(`/api/commands/pending/${verify.slaveId}`, key)).json();
  assert.equal(pending.commands.length, 0);

  // done 된 명령도 취소 불가 (409)
  const cmd2 = await (await post('/api/commands', { slaveId: verify.slaveId, action: 'fan' })).json();
  assert.equal((await post(`/api/commands/${cmd2.commandId}/done`, {})).status, 200);
  assert.equal((await post(`/api/commands/${cmd2.commandId}/cancel`, {})).status, 409);
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

test('알림 읽음 상태: unreadCount → 모두 읽음 → 신규 이벤트로 다시 증가', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const key = { 'x-session-key': verify.sessionKey };

  await post('/api/events', { slaveId: verify.slaveId, type: 'water_low' }, key);
  await post('/api/events', { slaveId: verify.slaveId, type: 'harvest_ready' }, key);

  // 읽지 않은 2건
  let n = await (await get(`/api/notifications/${u.id}`)).json();
  assert.equal(n.unreadCount, 2);
  assert.equal(n.lastReadAt, null);

  // 모두 읽음
  const read = await post(`/api/notifications/${u.id}/read`, {});
  assert.equal(read.status, 200);
  n = await (await get(`/api/notifications/${u.id}`)).json();
  assert.equal(n.unreadCount, 0);
  assert.ok(n.lastReadAt);

  // 신규 이벤트 → 다시 1건 (created_at 이 초 단위라 읽음 마커보다 확실히 늦게)
  await new Promise((r) => setTimeout(r, 1100));
  await post('/api/events', { slaveId: verify.slaveId, type: 'pest_detected' }, key);
  n = await (await get(`/api/notifications/${u.id}`)).json();
  assert.equal(n.unreadCount, 1);
});

test('가구 초대: 가족/친구가 Farmer·식물을 공동 소유', async () => {
  const owner = await (await post('/api/users', { nickname: '주인', region: 'Seoul' })).json();
  const friend = await (await post('/api/users', { nickname: '친구', region: 'Busan' })).json();

  const codeResp = await (await post('/api/pairing/code', { userId: owner.id })).json();
  const verify = await (await post('/api/pairing/verify', { code: codeResp.code })).json();
  await post('/api/plants', { userId: owner.id, slaveId: verify.slaveId, name: '공유토마토' });

  const invite = await (await post('/api/household/invite', { userId: owner.id })).json();
  assert.ok(invite.code);

  const accept = await post('/api/household/accept', { userId: friend.id, code: invite.code });
  assert.equal(accept.status, 200);

  const friendFarmers = await (await get(`/api/pairing/${friend.id}`)).json();
  assert.equal(friendFarmers.slaves.length, 1);
  assert.equal(friendFarmers.slaves[0].id, verify.slaveId);
  assert.equal(friendFarmers.slaves[0].shared, 1);

  const friendPlants = await (await get(`/api/plants/${friend.id}`)).json();
  assert.equal(friendPlants.plants.length, 1);
  assert.equal(friendPlants.plants[0].name, '공유토마토');
  assert.equal(friendPlants.plants[0].shared, 1);

  const hh = await (await get(`/api/household/${friend.id}`)).json();
  assert.equal(hh.role, 'member');
  assert.equal(hh.members.length, 2);
});

test('성장 사진첩: Farmer 업로드 후 식물별 조회', async () => {
  const u = await (await post('/api/users', { nickname: '앨범', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name: '방울토마토' })).json();

  const jpeg = Buffer.from(
    '/9j/4AAQSkZJRgABAQAAAQABAAD/2wAAAAD/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAEf/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPwB//9k=',
    'base64'
  );
  const fd = new FormData();
  fd.append('photo', new Blob([jpeg], { type: 'image/jpeg' }), 'day1.jpg');
  fd.append('plantId', plant.plantId);
  fd.append('takenAt', '2026-08-19 07:00');
  fd.append('location', 'Seoul');
  const up = await fetch(`${base}/api/photos/upload/${verify.slaveId}`, {
    method: 'POST',
    headers: { 'x-session-key': verify.sessionKey },
    body: fd,
  });
  assert.equal(up.status, 200);

  const album = await (await get(`/api/photos/plant/${plant.plantId}`)).json();
  assert.equal(album.photos.length, 1);
  assert.equal(album.photos[0].location, 'Seoul');
  assert.ok(album.photos[0].url.startsWith('/api/photos/file/'));
});
