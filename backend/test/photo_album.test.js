// 마스터 사진첩 — 썸네일 업로드 + 사용자별 조인 조회 (node:test, 외부 의존성 없음)
//  - upload: photo(필수) + thumb(선택) — 기존 photo-only 호환
//  - GET /api/photos/user/:userId — farmer→식물 이름 조인, 최신순, LIMIT 300
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-photo-album-${process.pid}.db`);
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
const get = (p) => fetch(base + p);

const JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wAAAAD/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAb/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAEf/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPwB//9k=',
  'base64'
);

async function setupPairedPlant(name = '바질') {
  const u = await (await post('/api/users', { nickname: '앨범테스트', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code, name: 'album-farmer' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name })).json();
  return { userId: u.id, slaveId: verify.slaveId, sessionKey: verify.sessionKey, plantId: plant.plantId };
}

async function uploadPhoto(slaveId, sessionKey, plantId, { takenAt, withThumb }) {
  const fd = new FormData();
  fd.append('photo', new Blob([JPEG], { type: 'image/jpeg' }), 'full.jpg');
  if (withThumb) fd.append('thumb', new Blob([JPEG], { type: 'image/jpeg' }), 'thumb.jpg');
  fd.append('plantId', plantId);
  fd.append('takenAt', takenAt);
  fd.append('location', 'Seoul');
  const res = await fetch(`${base}/api/photos/upload/${slaveId}`, {
    method: 'POST',
    headers: { 'x-session-key': sessionKey },
    body: fd,
  });
  return { res, body: await res.json() };
}

test('썸네일: photo+thumb 업로드 → thumb_url 반환, 파일 서빙', async () => {
  const { slaveId, sessionKey, plantId } = await setupPairedPlant();
  const { res, body } = await uploadPhoto(slaveId, sessionKey, plantId, { takenAt: '2026-11-20 08:00', withThumb: true });
  assert.equal(res.status, 200);
  assert.ok(body.url.startsWith('/api/photos/file/'));
  assert.ok(body.thumb_url && body.thumb_url !== body.url, 'thumb_url is distinct');

  const served = await fetch(base + body.thumb_url);
  assert.equal(served.status, 200);
  assert.equal(served.headers.get('content-type'), 'image/jpeg');
});

test('썸네일: photo-only (기존 슬레이브) 호환 — thumb_url null', async () => {
  const { slaveId, sessionKey, plantId } = await setupPairedPlant();
  const { res, body } = await uploadPhoto(slaveId, sessionKey, plantId, { takenAt: '2026-11-20 09:00', withThumb: false });
  assert.equal(res.status, 200);
  assert.equal(body.thumb_url, null);
});

test('사용자 사진첩: farmer→식물 이름 조인 + 최신순', async () => {
  const a = await setupPairedPlant('바질');
  const b = await setupPairedPlant('상추');
  // 같은 farmer(슬레이브)에 두 식물 — farmer별·식물별 그룹핑 재료 검증
  const plant2 = await (await post('/api/plants', { userId: a.userId, slaveId: a.slaveId, name: '상추' })).json();

  await uploadPhoto(a.slaveId, a.sessionKey, a.plantId, { takenAt: '2026-11-21 08:00', withThumb: true });
  await uploadPhoto(a.slaveId, a.sessionKey, plant2.plantId, { takenAt: '2026-11-22 08:00', withThumb: true });
  await uploadPhoto(b.slaveId, b.sessionKey, b.plantId, { takenAt: '2026-11-23 08:00', withThumb: false });

  const list = await (await get(`/api/photos/user/${a.userId}`)).json();
  // a 의 식물 2장 + b(다른 사용자) 1장 — b 는 a 의 가구가 아니므로 제외
  assert.equal(list.photos.length, 2);
  for (const ph of list.photos) {
    assert.ok(ph.plant_name, 'plant_name joined');
    assert.equal(ph.slave_name, 'album-farmer');
    assert.ok(ph.thumb_url, 'thumb_url present');
  }
  // 최신순 (created_at DESC)
  assert.ok(new Date(list.photos[0].created_at) >= new Date(list.photos[1].created_at));
  const names = list.photos.map((p) => p.plant_name).sort();
  assert.deepEqual(names, ['바질', '상추']);
});

test('사용자 사진첩: LIMIT 300 (직접 insert 305장)', async () => {
  const { slaveId, sessionKey, plantId, userId } = await setupPairedPlant();
  for (let i = 0; i < 305; i++) {
    db.prepare(
      'INSERT INTO plant_photos(id, slave_id, plant_id, filename, mime, size, created_at) VALUES(?,?,?,?,?,?,?)'
    ).run(`lim-${i}`, slaveId, plantId, `lim-${i}.jpg`, 'image/jpeg', 10, new Date(Date.now() + i).toISOString());
  }
  const list = await (await get(`/api/photos/user/${userId}`)).json();
  assert.equal(list.photos.length, 300);
});
