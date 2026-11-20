// offline sweeper + unpair 오르팬 정리 통합 테스트
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-sweep-test-${process.pid}.db`);
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

const post = (p, body, headers = {}) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
const get = (p, h = {}) => fetch(base + p, { headers: h });

async function mkSlave() {
  const u = await (await post('/api/users', { nickname: 'sw', region: 'Seoul' })).json();
  const c = await (await post('/api/pairing/code', { userId: u.id })).json();
  const v = await (await post('/api/pairing/verify', { code: c.code })).json();
  return { userId: u.id, slaveId: v.slaveId, sessionKey: v.sessionKey };
}

test('sweeper: 3분 이상 무신호면 offline, 최근 신호는 online 유지', async () => {
  const a = await mkSlave();
  const b = await mkSlave();

  // a 는 10분 전 신호로 되돌림
  db.prepare("UPDATE slaves SET online=1, last_seen=datetime('now','-10 minutes') WHERE id=?").run(a.slaveId);

  const { sweepOfflineSlaves } = await import('../src/lib/sweeper.js');
  const n = sweepOfflineSlaves();
  assert.equal(n, 1);
  assert.equal(db.prepare('SELECT online FROM slaves WHERE id=?').get(a.slaveId).online, 0);
  assert.equal(db.prepare('SELECT online FROM slaves WHERE id=?').get(b.slaveId).online, 1);

  // heartbeat 로 a 가 복구되면 다시 online
  await post('/api/pairing/heartbeat', { slaveId: a.slaveId, battery: 80 }, { 'x-session-key': a.sessionKey });
  assert.equal(db.prepare('SELECT online FROM slaves WHERE id=?').get(a.slaveId).online, 1);
});

test('unpair: slave + 식물 단위 데이터 포함 전 테이블 정리 (orphan 0개)', async () => {
  const { slaveId, sessionKey } = await mkSlave();
  const key = { 'x-session-key': sessionKey };

  const plant = await (await post('/api/plants', { userId: 'x', slaveId, name: '청상추' })).json();
  const pid = plant.plantId;

  // 이벤트/관수/명령 (API 경로로)
  await post('/api/events', { slaveId, plantId: pid, type: 'water_low' }, key);
  await post('/api/watering/log', { slaveId, plantId: pid, amountMl: 300 }, key);
  await post('/api/commands', { slaveId, plantId: pid, action: 'water', amountMl: 200 });

  // 미디어/진단 (multipart 없이 직접 — 정리 로직이 slave_id/plant_id 로 삭제하는지 검증)
  db.prepare('INSERT INTO plant_photos(id, slave_id, plant_id, filename, mime, size) VALUES(?,?,?,?,?,?)')
    .run('ph-1', slaveId, pid, 'a.jpg', 'image/jpeg', 100);
  db.prepare('INSERT INTO videos(id, slave_id, command_id, filename, mime, size, plant_id) VALUES(?,?,?,?,?,?,?)')
    .run('vid-1', slaveId, null, 'a.mp4', 'video/mp4', 100, pid);
  db.prepare('INSERT INTO plant_analysis(id, slave_id, plant_id, analysis_json) VALUES(?,?,?,?)')
    .run('an-1', slaveId, pid, '{"greenness":0.5}');
  db.prepare('INSERT INTO diagnosis_sessions(id, slave_id, plant_id, ai_label, ai_confidence) VALUES(?,?,?,?,?)')
    .run('diag-1', slaveId, pid, 'healthy', 0.9);
  db.prepare('INSERT INTO diagnosis_frames(id, session_id, frame_index, filename) VALUES(?,?,?,?)')
    .run('frame-1', 'diag-1', 0, 'f0.jpg');
  db.prepare('INSERT INTO harvests(id, user_id, slave_id, plant_id, quantity) VALUES(?,?,?,?,?)')
    .run('h-1', 'x', slaveId, pid, 100);

  // unpair
  const r = await fetch(base + `/api/pairing/${slaveId}`, { method: 'DELETE' });
  assert.equal(r.status, 200);

  const cnt = (sql, ...args) => db.prepare(sql).get(...args).n;
  assert.equal(cnt('SELECT COUNT(*) n FROM slaves WHERE id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM plants WHERE id=?', pid), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM policies WHERE slave_id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM events WHERE slave_id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM waterings WHERE slave_id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM commands WHERE slave_id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM harvests WHERE slave_id=?', slaveId), 0);
  // 식물 단위 미디어/진단
  assert.equal(cnt('SELECT COUNT(*) n FROM plant_photos WHERE plant_id=?', pid), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM videos WHERE plant_id=?', pid), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM plant_analysis WHERE plant_id=?', pid), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM diagnosis_sessions WHERE slave_id=?', slaveId), 0);
  assert.equal(cnt('SELECT COUNT(*) n FROM diagnosis_frames WHERE session_id=?', 'diag-1'), 0);

  // unknown slave → 404
  assert.equal((await fetch(base + '/api/pairing/ghost-ghost-ghost', { method: 'DELETE' })).status, 404);
});
