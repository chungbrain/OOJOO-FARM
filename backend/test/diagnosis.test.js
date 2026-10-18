// 진단 세션(diagnosis) 통합 테스트 (node:test, 외부 의존성 없음)
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-diag-test-${process.pid}.db`);
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
const get = (p, headers = {}) => fetch(base + p, { headers });

// 1x1 JPEG 더미 프레임
const jpeg = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wAAAAD/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAEf/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPwB//9k=',
  'base64'
);

async function setupPairedPlant() {
  const u = await (await post('/api/users', { nickname: '진단', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name: '상추' })).json();
  return { slaveId: verify.slaveId, sessionKey: verify.sessionKey, plantId: plant.plantId };
}

test('진단 세션: 업로드 → 식물별 조회 → 확정', async () => {
  const { slaveId, sessionKey, plantId } = await setupPairedPlant();
  const key = { 'x-session-key': sessionKey };

  // 업로드: 프레임 2개 + framesMeta
  const fd = new FormData();
  fd.append('frames', new Blob([jpeg], { type: 'image/jpeg' }), 'f0.jpg');
  fd.append('frames', new Blob([jpeg], { type: 'image/jpeg' }), 'f1.jpg');
  fd.append('plantId', plantId);
  fd.append('modelId', 'OJP1-lettuce');
  fd.append('aiLabel', 'water_low');
  fd.append('aiConfidence', '0.87');
  fd.append('frameCount', '2');
  fd.append('videoId', 'vid123');
  fd.append('commandId', 'cmd456');
  fd.append('framesMeta', JSON.stringify([
    { index: 0, timestampMs: 0, condition: 'water_low', confidence: 0.9 },
    { index: 1, timestampMs: 500, condition: 'water_low', confidence: 0.84 },
  ]));
  const up = await fetch(`${base}/api/diagnosis/sessions/${slaveId}`, {
    method: 'POST',
    headers: key,
    body: fd,
  });
  assert.equal(up.status, 200);
  const { sessionId } = await up.json();
  assert.ok(sessionId);

  // 식물별 조회
  const list = await (await get(`/api/diagnosis/plant/${plantId}`)).json();
  assert.equal(list.sessions.length, 1);
  const s = list.sessions[0];
  assert.equal(s.id, sessionId);
  assert.equal(s.slaveId, slaveId);
  assert.equal(s.plantId, plantId);
  assert.equal(s.videoId, 'vid123');
  assert.equal(s.modelId, 'OJP1-lettuce');
  assert.equal(s.aiLabel, 'water_low');
  assert.equal(s.aiConfidence, 0.87);
  assert.equal(s.humanLabel, null);
  assert.equal(s.frames.length, 2);
  assert.equal(s.frames[0].index, 0);
  assert.equal(s.frames[0].timestampMs, 0);
  assert.equal(s.frames[0].condition, 'water_low');
  assert.equal(s.frames[0].confidence, 0.9);
  assert.equal(s.frames[0].url, `/api/diagnosis/frame/${sessionId}/0`);
  assert.equal(s.frames[1].url, `/api/diagnosis/frame/${sessionId}/1`);

  // 프레임 이미지 서빙
  const img = await get(`/api/diagnosis/frame/${sessionId}/0`);
  assert.equal(img.status, 200);
  assert.equal(img.headers.get('content-type'), 'image/jpeg');
  const buf = Buffer.from(await img.arrayBuffer());
  assert.ok(buf.length > 0);

  // 없는 프레임 404
  assert.equal((await get(`/api/diagnosis/frame/${sessionId}/9`)).status, 404);

  // 확정: 잘못된 라벨 400
  assert.equal((await post(`/api/diagnosis/sessions/${sessionId}/confirm`, { humanLabel: 'dying', confirmedBy: 'u1' })).status, 400);

  // 확정: 정상
  const ok = await post(`/api/diagnosis/sessions/${sessionId}/confirm`, { humanLabel: 'pest', confirmedBy: 'master1' });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).ok, true);

  const after = await (await get(`/api/diagnosis/plant/${plantId}`)).json();
  assert.equal(after.sessions[0].humanLabel, 'pest');
  assert.equal(after.sessions[0].confirmedBy, 'master1');
  assert.ok(after.sessions[0].confirmedAt);
});

test('진단 세션: 검증 실패 케이스', async () => {
  const { slaveId, sessionKey, plantId } = await setupPairedPlant();
  const key = { 'x-session-key': sessionKey };

  // plantId 누락 → 400
  const fdNoPlant = new FormData();
  fdNoPlant.append('frames', new Blob([jpeg], { type: 'image/jpeg' }), 'f0.jpg');
  fdNoPlant.append('aiLabel', 'healthy');
  assert.equal((await fetch(`${base}/api/diagnosis/sessions/${slaveId}`, { method: 'POST', headers: key, body: fdNoPlant })).status, 400);

  // aiLabel 누락 → 400
  const fdNoLabel = new FormData();
  fdNoLabel.append('frames', new Blob([jpeg], { type: 'image/jpeg' }), 'f0.jpg');
  fdNoLabel.append('plantId', plantId);
  assert.equal((await fetch(`${base}/api/diagnosis/sessions/${slaveId}`, { method: 'POST', headers: key, body: fdNoLabel })).status, 400);

  // 파일 누락 → 400
  const fdNoFiles = new FormData();
  fdNoFiles.append('plantId', plantId);
  fdNoFiles.append('aiLabel', 'healthy');
  assert.equal((await fetch(`${base}/api/diagnosis/sessions/${slaveId}`, { method: 'POST', headers: key, body: fdNoFiles })).status, 400);

  // 세션키 없음 → 401
  const fdAuth = new FormData();
  fdAuth.append('frames', new Blob([jpeg], { type: 'image/jpeg' }), 'f0.jpg');
  fdAuth.append('plantId', plantId);
  fdAuth.append('aiLabel', 'healthy');
  assert.equal((await fetch(`${base}/api/diagnosis/sessions/${slaveId}`, { method: 'POST', body: fdAuth })).status, 401);

  // 존재하지 않는 세션 확정 → 404
  assert.equal((await post('/api/diagnosis/sessions/nonexistent12/confirm', { humanLabel: 'pest', confirmedBy: 'u1' })).status, 404);
});
