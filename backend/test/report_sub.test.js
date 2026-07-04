// 리포트 + 구독 통합 테스트
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-rep-${process.pid}.db`);
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
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) { try { fs.rmSync(f); } catch {} }
});

const get = (p, h) => fetch(base + p, { headers: h });
const post = (p, b, h) => fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json', ...h }, body: JSON.stringify(b) });

test('리포트: 관수/이벤트 7일 요약 집계', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const v = await (await post('/api/pairing/verify', { code })).json();
  const key = { 'x-session-key': v.sessionKey };

  await post('/api/watering/log', { slaveId: v.slaveId, amountMl: 300, source: 'auto' }, key);
  await post('/api/watering/log', { slaveId: v.slaveId, amountMl: 200, source: 'manual' }, key);
  await post('/api/events', { slaveId: v.slaveId, type: 'harvest_ready' }, key);
  await post('/api/events', { slaveId: v.slaveId, type: 'pest_detected' }, key);

  const rep = await (await get(`/api/report/${v.slaveId}`)).json();
  assert.equal(rep.watering.count, 2);
  assert.equal(rep.watering.totalMl, 500);
  assert.equal(rep.watering.autoCount, 1);
  assert.equal(rep.harvestReady, 1);
  assert.equal(rep.pestDetected, 1);
  assert.ok(rep.lastWatering);
});

test('구독: 기본 free → premium 전환', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();

  const def = await (await get(`/api/subscription/${u.id}`)).json();
  assert.equal(def.plan, 'free');
  assert.equal(def.maxFarmers, 2);

  const up = await (await post('/api/subscription', { userId: u.id, plan: 'premium' })).json();
  assert.equal(up.plan, 'premium');
  assert.equal(up.detailedReport, true);

  const after = await (await get(`/api/subscription/${u.id}`)).json();
  assert.equal(after.plan, 'premium');

  const plans = await (await get('/api/subscription/plans')).json();
  assert.ok(plans.plans.length >= 2);
});
