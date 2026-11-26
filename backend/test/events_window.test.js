// GET /api/events/:slaveId 의 ?days=N 창 계약 (node:test, 외부 의존성 없음)
//  - 파라미터 없음/잘못된 값 → 기존 계약 그대로 (최신 50건, days 필드 없음)
//  - ?days=N → 창 안 이벤트 전부 + days 반환, 50건 미만이면 최신 50건으로 채움
//  - 창 안 이벤트가 상한(1000)에 걸리면 truncated=true
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-events-window-${process.pid}.db`);
process.env.DB_PATH = tmpDb;

let server;
let base;
let db;

before(async () => {
  const { default: app } = await import('../src/app.js');
  db = (await import('../src/db.js')).default;
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

const get = async (p) => (await fetch(base + p)).json();

// created_at 을 'now' 기준 상대 시각으로 넣는다 (예: '-3 days')
function insertEvent(slaveId, id, type, ago) {
  db.prepare("INSERT INTO events(id, slave_id, plant_id, type, payload, created_at) VALUES(?,?,?,?,?, datetime('now', ?))")
    .run(id, slaveId, null, type, '{}', ago);
}

test('파라미터 없음 → 기존 계약: 최신 50건, days 필드 없음', async () => {
  const s = 'ev-legacy';
  for (let i = 0; i < 60; i++) insertEvent(s, `${s}-${i}`, 'auto_water', `-${i} hours`);
  const body = await get(`/api/events/${s}`);
  assert.equal(body.events.length, 50);
  assert.equal(body.days, undefined);
  assert.equal(body.events[0].id, `${s}-0`, '최신순');
});

test('잘못된 days (0, 91, 문자, 소수) → 기존 계약으로 폴백', async () => {
  const s = 'ev-legacy';
  for (const q of ['0', '91', 'abc', '1.5']) {
    const body = await get(`/api/events/${s}?days=${q}`);
    assert.equal(body.days, undefined, `days=${q}`);
    assert.equal(body.events.length, 50, `days=${q}`);
  }
});

test('?days=14 → 창 안 이벤트 전부 + 부족하면 최신 50건으로 채움', async () => {
  const s = 'ev-window';
  insertEvent(s, `${s}-new1`, 'pest_detected', '-1 hours');
  insertEvent(s, `${s}-new2`, 'auto_water', '-3 days');
  insertEvent(s, `${s}-old`, 'battery_low', '-20 days');
  const body = await get(`/api/events/${s}?days=14`);
  assert.equal(body.days, 14);
  assert.equal(body.truncated, false);
  const ids = body.events.map((e) => e.id);
  assert.deepEqual(ids, [`${s}-new1`, `${s}-new2`, `${s}-old`], '창 안 2건 + 채움 1건, 최신순');
});

test('?days=7 → 창 안 이벤트가 50건을 넘어도 전부 반환 (기존 50건 상한 해제)', async () => {
  const s = 'ev-many';
  for (let i = 0; i < 80; i++) insertEvent(s, `${s}-${i}`, 'health_change', `-${i * 60} minutes`);
  insertEvent(s, `${s}-old`, 'auto_water', '-30 days');
  const body = await get(`/api/events/${s}?days=7`);
  assert.equal(body.days, 7);
  assert.equal(body.events.length, 80, '창 밖 이벤트는 채우지 않음 (창 안이 50건 이상)');
  assert.ok(!body.events.some((e) => e.id === `${s}-old`));
});

test('창 안 이벤트가 상한(1000)에 걸리면 truncated=true', async () => {
  const s = 'ev-cap';
  const ins = db.prepare("INSERT INTO events(id, slave_id, plant_id, type, payload, created_at) VALUES(?,?,?,?,?, datetime('now', ?))");
  db.exec('BEGIN');
  for (let i = 0; i < 1001; i++) ins.run(`${s}-${i}`, s, null, 'capture', '{}', `-${i} minutes`);
  db.exec('COMMIT');
  const body = await get(`/api/events/${s}?days=2`);
  assert.equal(body.events.length, 1000);
  assert.equal(body.truncated, true);
});
