// 식물카드 나눔/판매 — 커뮤니티 게시물에 식물·성장스토리 연결 (node:test, 외부 의존성 없음)
//  - POST /posts: plantId/videoId (존재 검증)
//  - decorate: plant_name/plant_species/video_url 조인
//  - PATCH /posts/:id/video — 동영상 준비 후 첨부
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-comm-plant-${process.pid}.db`);
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

const post = (p, body) =>
  fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const patch = (p, body) =>
  fetch(base + p, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p) => fetch(base + p);

async function setupUserPlant() {
  const u = await (await post('/api/users', { nickname: '이웃카드', region: 'Seoul' })).json();
  const code = (await (await post('/api/pairing/code', { userId: u.id })).json()).code;
  const verify = await (await post('/api/pairing/verify', { code, name: 'card-farmer' })).json();
  const plant = await (await post('/api/plants', { userId: u.id, slaveId: verify.slaveId, name: '딸기', species: 'strawberry' })).json();
  return { userId: u.id, slaveId: verify.slaveId, plantId: plant.plantId };
}

function insertVideo(id, plantId = null) {
  db.prepare('INSERT INTO videos(id, slave_id, filename, mime, size, kind, photo_count, plant_id) VALUES(?,?,?,?,?,?,?,?)')
    .run(id, 'test-slave', `${id}.mp4`, 'video/mp4', 1024, 'growth', 12, plantId);
}

test('식물카드 게시물: plantId+videoId 작성 → 피드에 식물/동영상 정보 조인', async () => {
  const { userId, plantId } = await setupUserPlant();
  insertVideo('vid-a', plantId);

  const created = await (await post('/api/community/posts', {
    userId, type: 'share', title: '딸기 나눔', region: 'Seoul',
    plantId, videoId: 'vid-a', description: '성장에요',
  })).json();
  assert.ok(created.postId);

  const feed = await (await get('/api/community/posts?region=Seoul')).json();
  const p = feed.posts.find((x) => x.id === created.postId);
  assert.ok(p);
  assert.equal(p.plant_id, plantId);
  assert.equal(p.plant_name, '딸기');
  assert.equal(p.plant_species, 'strawberry');
  assert.equal(p.video_url, '/api/videos/file/vid-a.mp4');
});

test('식물카드 게시물: 존재하지 않는 plantId/videoId → 400', async () => {
  const { userId } = await setupUserPlant();
  const bad1 = await post('/api/community/posts', { userId, type: 'sell', title: 'x', plantId: 'nope' });
  assert.equal(bad1.status, 400);
  const bad2 = await post('/api/community/posts', { userId, type: 'sell', title: 'x', videoId: 'nope' });
  assert.equal(bad2.status, 400);
});

test('성장스토리 동영상 첨부: PATCH /posts/:id/video', async () => {
  const { userId } = await setupUserPlant();
  const created = await (await post('/api/community/posts', {
    userId, type: 'share', title: '성장스토리 기다리는 게시물', region: 'Daegu',
  })).json();

  let detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.equal(detail.post.video_url, null);

  insertVideo('vid-b');
  const ok = await (await patch(`/api/community/posts/${created.postId}/video`, { videoId: 'vid-b' })).json();
  assert.equal(ok.ok, true);

  detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.equal(detail.post.video_id, 'vid-b');
  assert.equal(detail.post.video_url, '/api/videos/file/vid-b.mp4');

  // 잘못된 동영상 id → 400 + 기존값 유지
  const bad = await patch(`/api/community/posts/${created.postId}/video`, { videoId: 'nope' });
  assert.equal(bad.status, 400);
  detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.equal(detail.post.video_id, 'vid-b');
});

test('기존 호환: plantId 없는 게시물은 plant_name/video_url null', async () => {
  const { userId } = await setupUserPlant();
  const created = await (await post('/api/community/posts', {
    userId, type: 'buy', title: '상추 사려요', region: 'Seoul',
  })).json();
  const detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.equal(detail.post.plant_name, null);
  assert.equal(detail.post.video_url, null);
});
