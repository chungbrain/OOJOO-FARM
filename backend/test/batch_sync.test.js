// 오프라인 일괄 동기화 (POST /api/events/batch, PRD 5.5) 통합 테스트
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-batch-test-${process.pid}.db`);
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
const get = (p) => fetch(base + p);

async function mkSlave() {
  const u = await (await post('/api/users', { nickname: 'batch', region: 'Seoul' })).json();
  const c = await (await post('/api/pairing/code', { userId: u.id })).json();
  const v = await (await post('/api/pairing/verify', { code: c.code })).json();
  return { userId: u.id, slaveId: v.slaveId, sessionKey: v.sessionKey };
}

test('batch: 세션키 없으면 401', async () => {
  const { slaveId } = await mkSlave();
  const r = await post('/api/events/batch', { slaveId, events: [{ type: 'water_low' }] });
  assert.equal(r.status, 401);
});

test('batch: events+waterings 일괄 저장 (수락 카운트 + 조회 확인)', async () => {
  const { slaveId, sessionKey } = await mkSlave();
  const h = { 'x-session-key': sessionKey };
  const r = await post('/api/events/batch', {
    slaveId,
    events: [
      { type: 'water_low', plantId: 'p1', payload: { greenness: 0.3 } },
      { type: 'harvest_ready', plantId: 'p2' },
    ],
    waterings: [{ amountMl: 250, source: 'auto' }],
  }, h);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.accepted, 3);
  assert.equal(j.events, 2);
  assert.equal(j.waterings, 1);

  const ev = await (await get(`/api/events/${slaveId}`)).json();
  assert.equal(ev.events.length, 2);
  const types = ev.events.map(e => e.type).sort();
  assert.deepEqual(types, ['harvest_ready', 'water_low']);
});

test('batch: 빈 요청 400, 한계 초과 400', async () => {
  const { slaveId, sessionKey } = await mkSlave();
  const h = { 'x-session-key': sessionKey };
  assert.equal((await post('/api/events/batch', { slaveId }, h)).status, 400);
  const tooMany = { slaveId, events: Array.from({ length: 501 }, (_, i) => ({ type: `e${i}`.slice(0, 50) })) };
  assert.equal((await post('/api/events/batch', tooMany, h)).status, 400);
});

test('batch: 중간에 잘못된 항목이면 전부 롤백 (부분 성공 금지)', async () => {
  const { slaveId, sessionKey } = await mkSlave();
  const h = { 'x-session-key': sessionKey };
  const r = await post('/api/events/batch', {
    slaveId,
    events: [
      { type: 'water_low' },
      { type: '' }, // invalid
    ],
  }, h);
  assert.equal(r.status, 400);
  const ev = await (await get(`/api/events/${slaveId}`)).json();
  assert.equal(ev.events.length, 0); // 첫 항목도 롤백
});
