// 입력 검증 통합 테스트 (node:test)
// - 명령 action whitelist / amountMl / weatherFactor 범위
// - 이벤트 type 길이
// - 커뮤니티 lat/lon 범위
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-validation-test-${process.pid}.db`);
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

async function mkUserAndSlave() {
  const u = await (await post('/api/users', { nickname: 'val', region: 'Seoul' })).json();
  const c = await (await post('/api/pairing/code', { userId: u.id })).json();
  const v = await (await post('/api/pairing/verify', { code: c.code })).json();
  return { userId: u.id, slaveId: v.slaveId, sessionKey: v.sessionKey };
}

test('commands: whitelist 밖 action은 400', async () => {
  const { slaveId } = await mkUserAndSlave();
  for (const action of ['explode', 'WATER', '', 'water ']) {
    const r = await post('/api/commands', { slaveId, action });
    assert.equal(r.status, 400, `action=${JSON.stringify(action)}`);
  }
});

test('commands: amountMl 범위 (<=0, >30000, 비숫수)는 400', async () => {
  const { slaveId } = await mkUserAndSlave();
  for (const amountMl of [0, -5, 30001, 'abc', null]) {
    const r = await post('/api/commands', { slaveId, action: 'water', amountMl });
    assert.equal(r.status, 400, `amountMl=${JSON.stringify(amountMl)}`);
  }
});

test('commands: weatherFactor 범위 (<=0, >3)는 400, 정상 조합은 200', async () => {
  const { slaveId, plantId } = { ...await mkUserAndSlave(), plantId: null };
  for (const weatherFactor of [0, -1, 4]) {
    const r = await post('/api/commands', { slaveId, action: 'water', weatherFactor });
    assert.equal(r.status, 400, `weatherFactor=${weatherFactor}`);
  }
  const ok = await post('/api/commands', { slaveId, action: 'water', amountMl: 450, weatherFactor: 1.2 });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).status, 'queued');
});

test('watering log: 범위를 벗어난 보고는 400 (세션키 인증 포함)', async () => {
  const { slaveId, sessionKey } = await mkUserAndSlave();
  const h = { 'x-session-key': sessionKey };
  const bad = await post('/api/watering/log', { slaveId, amountMl: 999999 }, h);
  assert.equal(bad.status, 400);
  const ok = await post('/api/watering/log', { slaveId, amountMl: 300, source: 'auto' }, h);
  assert.equal(ok.status, 200);
});

test('events: type은 string 1..50자만 허용', async () => {
  const { slaveId } = await mkUserAndSlave();
  const longType = 'x'.repeat(51);
  const bad = await post('/api/events', { slaveId, type: longType });
  assert.equal(bad.status, 400);
  const ok = await post('/api/events', { slaveId, type: 'water_low' });
  assert.equal(ok.status, 200);
});

test('community posts: lat/lon 범위 초과 또는 NaN 은 400', async () => {
  const { userId } = await mkUserAndSlave();
  const mk = (lat, lon) => post('/api/community/posts', { userId, type: 'share', title: 't', lat, lon });
  assert.equal((await mk(95, 0)).status, 400);
  assert.equal((await mk(0, -190)).status, 400);
  assert.equal((await mk('abc', 127)).status, 400);
  assert.equal((await mk(37.5, 127.0)).status, 200);
  assert.equal((await mk(null, null)).status, 200); // 좌표 없는 지역 피드
});
