// slave 목록의 pairing_code 계약 (node:test, 외부 의존성 없음)
//  - 페어링 후 목록에 해당 farmer의 pairing_code 노출 (사용된 코드 그대로)
//  - farmer 2대 → 각각 페어링한 코드 1:1 매칭
//  - unpair 후 → 목록에서 사라짐, 잔존(pairings) 행이 다른 farmer에 새지 않음
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-pairing-code-${process.pid}.db`);
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
const del = (p) => fetch(base + p, { method: 'DELETE' });
const get = (p) => fetch(base + p);

// 페어링 1회: 코드 생성 → verify → { code, slaveId }
async function pair(userId) {
  const code = (await (await post('/api/pairing/code', { userId })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code })).json();
  return { code, slaveId: verify.slaveId };
}

test('페어링 후 slave 목록에 pairing_code 노출 (사용된 코드 그대로)', async () => {
  const u = await (await post('/api/users', { nickname: 'codeowner', region: 'Seoul' })).json();
  const { code, slaveId } = await pair(u.id);

  const res = await (await get(`/api/pairing/${u.id}`)).json();
  assert.equal(res.slaves.length, 1);
  assert.equal(res.slaves[0].id, slaveId);
  assert.equal(res.slaves[0].pairing_code, code);
});

test('farmer 2대 — 각각 페어링 코드 1:1 매칭', async () => {
  const u = await (await post('/api/users', { nickname: 'twofarmers', region: 'Busan' })).json();
  const a = await pair(u.id);
  const b = await pair(u.id);

  const res = await (await get(`/api/pairing/${u.id}`)).json();
  assert.equal(res.slaves.length, 2);
  assert.equal(res.slaves[0].pairing_code, a.code);
  assert.equal(res.slaves[1].pairing_code, b.code);
  assert.notEqual(a.code, b.code);
});

test('unpair 후 — 목록에서 사라지고 잔존 코드가 다른 farmer에 안 샌다', async () => {
  const u = await (await post('/api/users', { nickname: 'unpairer', region: 'Incheon' })).json();
  const a = await pair(u.id);
  const b = await pair(u.id);

  // a 해제
  const r = await del(`/api/pairing/${a.slaveId}`);
  assert.equal(r.status, 200);

  const res = await (await get(`/api/pairing/${u.id}`)).json();
  assert.equal(res.slaves.length, 1);
  const rest = res.slaves[0];
  assert.equal(rest.id, b.slaveId);
  // a의 코드가 b에 새거나 null이 되어서는 안 됨
  assert.equal(rest.pairing_code, b.code);
  assert.notEqual(rest.pairing_code, a.code);
});
