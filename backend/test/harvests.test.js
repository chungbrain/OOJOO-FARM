// 수확 기록 API 통합 테스트 (PRD 4.7)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-harvest-test-${process.pid}.db`);
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

const post = (p, body) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p) => fetch(base + p);

test('수확 기록: 저장 후 식물별/사용자별 조회 (수량/날짜/사진)', async () => {
  const u = await (await post('/api/users', { nickname: 'harvest', region: 'Seoul' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, name: '딸기', species: 'strawberry' })).json();

  // 기본 저장
  const h1 = await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: 250, unit: 'g', note: '첫 수확' });
  assert.equal(h1.status, 200);
  // 사후 기록 (ISO date)
  const h2 = await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: 1.2, unit: 'kg', date: '2026-11-01T08:30:00Z', photo: 'photo-1.jpg' });
  assert.equal(h2.status, 200);

  // 식물별 이력
  const byPlant = await (await get(`/api/harvests/plant/${plant.plantId}`)).json();
  assert.equal(byPlant.harvests.length, 2);

  // 사용자 요약 — 식물명 조인
  const byUser = await (await get(`/api/harvests/user/${u.id}`)).json();
  assert.equal(byUser.harvests.length, 2);
  const withPhoto = byUser.harvests.find(h => h.photo === 'photo-1.jpg');
  assert.ok(withPhoto);
  assert.equal(withPhoto.plant_name, '딸기');
  assert.equal(withPhoto.quantity, 1.2);
});

test('수확 기록: 검증 (수량 <=0, 잘못된 date, 필수 누락)', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, name: '바질' })).json();
  assert.equal((await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: 0 })).status, 400);
  assert.equal((await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: -3 })).status, 400);
  assert.equal((await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: 'abc' })).status, 400);
  assert.equal((await post('/api/harvests', { userId: u.id, plantId: plant.plantId, quantity: 5, date: 'not-a-date' })).status, 400);
  assert.equal((await post('/api/harvests', { userId: u.id, quantity: 5 })).status, 400);
  assert.equal((await post('/api/harvests', { plantId: plant.plantId, quantity: 5 })).status, 400);
});
