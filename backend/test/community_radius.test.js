// 커뮤니티 피드 — 위치(lat/lon+radius) 검색 계약 (node:test, 외부 의존성 없음)
//  - 반경 내 위치 있는 글 → 조회, 반경 밖 → 제외
//  - 위치 없는 글 → 반경 검색에서 제외 (region 피드만 사용)
//  - 명시적 radius(1..50000m) 동작, 기본 5km
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-comm-radius-${process.pid}.db`);
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

const USER_LAT = 37.5; // 서울 기준
const USER_LON = 127.0;

test('반경 검색: 내 주변(5km)에 위치 있는 글만, 먼 글/위치 없는 글 제외', async () => {
  const u = await (await post('/api/users', { nickname: '반경테스트', region: 'Seoul' })).json();
  // 근처(약 1.3km) — farmer 위치로 둔 글
  const near = await (await post('/api/community/posts', {
    userId: u.id, type: 'sell', title: '근처 판매', region: 'Seoul',
    lat: 37.51, lon: 127.01,
  })).json();
  // 멀음(약 22km) — 같은 region
  const far = await (await post('/api/community/posts', {
    userId: u.id, type: 'share', title: '먼 곳 나눔', region: 'Seoul',
    lat: 37.7, lon: 127.0,
  })).json();
  // 위치 없는 글 — region만
  const noLoc = await (await post('/api/community/posts', {
    userId: u.id, type: 'buy', title: '위치없는 구매', region: 'Seoul',
  })).json();

  const res = await (await get(
    `/api/community/posts?lat=${USER_LAT}&lon=${USER_LON}`
  )).json();
  const ids = res.posts.map((p) => p.id);
  assert.ok(ids.includes(near.postId), 'near post found');
  assert.ok(!ids.includes(far.postId), 'far post excluded by default 5km');
  assert.ok(!ids.includes(noLoc.postId), 'location-less post excluded from radius search');
});

test('반경 검색: 명시적 radius — 1km면 근처 글도 제외, 10km면 포함', async () => {
  const u = await (await post('/api/users', { nickname: '반경테스트2', region: 'Seoul' })).json();
  const near = await (await post('/api/community/posts', {
    userId: u.id, type: 'sell', title: '1.3km 글', region: 'Seoul',
    lat: 37.51, lon: 127.01,
  })).json();

  const r1 = await (await get(`/api/community/posts?lat=${USER_LAT}&lon=${USER_LON}&radius=1000`)).json();
  assert.ok(!r1.posts.some((p) => p.id === near.postId), '1km radius excludes 1.3km post');

  const r10 = await (await get(`/api/community/posts?lat=${USER_LAT}&lon=${USER_LON}&radius=10000`)).json();
  assert.ok(r10.posts.some((p) => p.id === near.postId), '10km radius includes 1.3km post');
});

test('반경 검색: 무효 radius → 400', async () => {
  const bad1 = await get(`/api/community/posts?lat=${USER_LAT}&lon=${USER_LON}&radius=0`);
  assert.equal(bad1.status, 400);
  const bad2 = await get(`/api/community/posts?lat=${USER_LAT}&lon=${USER_LON}&radius=999999`);
  assert.equal(bad2.status, 400);
});
