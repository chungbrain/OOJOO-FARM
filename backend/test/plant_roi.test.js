// 식물별 RoI(관심영역) — 마스터 설정 → 저장/검증/반환 (node:test, 외부 의존성 없음)
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-plant-roi-${process.pid}.db`);
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
const put = (p, body) =>
  fetch(base + p, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p) => fetch(base + p);

async function setupPairedPlant() {
  const u = await (await post('/api/users', { nickname: 'RoI테스트', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code, name: 'Roi-Farmer' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name: '바질', species: 'basil' })).json();
  return { userId: u.id, slaveId: verify.slaveId, plantId: plant.plantId };
}

test('RoI: 설정 → 식물 상세/목록/슬레이브 목록에서 반환', async () => {
  const { userId, slaveId, plantId } = await setupPairedPlant();
  const roi = { x: 0.1, y: 0.2, w: 0.5, h: 0.4 };

  const set = await (await put(`/api/plants/plant/${plantId}`, { roi })).json();
  assert.equal(set.ok, true);

  const detail = await (await get(`/api/plants/plant/${plantId}`)).json();
  assert.deepEqual(detail.roi, roi);

  const userList = await (await get(`/api/plants/${userId}`)).json();
  assert.deepEqual(userList.plants.find((p) => p.id === plantId).roi, roi);

  // 슬레이브가 받는 목록에도 — 슬레이브가 이 값으로 RoiStore 를 동기화한다
  const slaveList = await (await get(`/api/plants/slave/${slaveId}`)).json();
  assert.deepEqual(slaveList.plants.find((p) => p.id === plantId).roi, roi);
});

test('RoI: 다른 필드 수정과 병행 (roi 생략 시 유지)', async () => {
  const { plantId } = await setupPairedPlant();
  await put(`/api/plants/plant/${plantId}`, { roi: { x: 0, y: 0, w: 0.3, h: 0.3 } });
  const up = await (await put(`/api/plants/plant/${plantId}`, { name: '새이름' })).json();
  assert.equal(up.ok, true);
  const detail = await (await get(`/api/plants/plant/${plantId}`)).json();
  assert.equal(detail.name, '새이름');
  assert.deepEqual(detail.roi, { x: 0, y: 0, w: 0.3, h: 0.3 });
});

test('RoI: roi:null 로 삭제', async () => {
  const { plantId } = await setupPairedPlant();
  await put(`/api/plants/plant/${plantId}`, { roi: { x: 0.1, y: 0.1, w: 0.5, h: 0.5 } });
  await put(`/api/plants/plant/${plantId}`, { roi: null });
  const detail = await (await get(`/api/plants/plant/${plantId}`)).json();
  assert.equal(detail.roi, null);
});

test('RoI: 무효 좌표는 400 + 기존값 유지', async () => {
  const { plantId } = await setupPairedPlant();
  await put(`/api/plants/plant/${plantId}`, { roi: { x: 0.1, y: 0.1, w: 0.5, h: 0.5 } });

  const cases = [
    { x: -0.1, y: 0, w: 0.5, h: 0.5 },      // 범위를 벗어남
    { x: 0.6, y: 0.6, w: 0.5, h: 0.5 },     // 프레임 밖으로 나감
    { x: 0, y: 0, w: 0.01, h: 0.5 },        // 최소 크기 미만 (2% 룰 — slave RoiStore.isValid 와 동일)
    { x: 'a', y: 0, w: 0.5, h: 0.5 },       // 숫자가 아님
    'not-an-object',
  ];
  for (const bad of cases) {
    const res = await put(`/api/plants/plant/${plantId}`, { roi: bad });
    assert.equal(res.status, 400, `reject ${JSON.stringify(bad)}`);
  }
  const detail = await (await get(`/api/plants/plant/${plantId}`)).json();
  assert.deepEqual(detail.roi, { x: 0.1, y: 0.1, w: 0.5, h: 0.5 });
});

test('RoI: 경계값 (0..1 전체, 최소 2%) 허용', async () => {
  const { plantId } = await setupPairedPlant();
  for (const roi of [
    { x: 0, y: 0, w: 1, h: 1 },
    { x: 0.98, y: 0.98, w: 0.02, h: 0.02 },
  ]) {
    const res = await put(`/api/plants/plant/${plantId}`, { roi });
    assert.equal(res.status, 200, `accept ${JSON.stringify(roi)}`);
  }
  const detail = await (await get(`/api/plants/plant/${plantId}`)).json();
  assert.deepEqual(detail.roi, { x: 0.98, y: 0.98, w: 0.02, h: 0.02 });
});
