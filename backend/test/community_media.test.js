// 이웃 글 첨부 사진·동영상 (node:test, 외부 의존성 없음)
//  - POST /api/community/media: 업로드 (형식·크기·사용자 검증, 썸네일)
//  - POST /api/community/posts: mediaIds 로 첨부 (소유·중복·개수 검증), 피드/상세에 media 배열
//  - GET  /api/community/media/file/:name: 등록 파일만, Range 지원
//  - DELETE /api/community/media/:id: 붙기 전 내 업로드만
//  - sweepOrphanMedia: 글에 안 붙은 오래된 업로드 정리
// 실행: npm test   (node --experimental-sqlite --test)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-comm-media-${process.pid}.db`);
const mediaDir = path.join(os.tmpdir(), `oojoo-comm-media-${process.pid}`);
process.env.DB_PATH = tmpDb;
process.env.COMMUNITY_MEDIA_DIR = mediaDir;

let server;
let base;
let db;
let sweepOrphanMedia;

before(async () => {
  const { default: app } = await import('../src/app.js');
  db = (await import('../src/db.js')).default;
  ({ sweepOrphanMedia } = await import('../src/lib/communityMedia.js'));
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
  try { fs.rmSync(mediaDir, { recursive: true, force: true }); } catch {}
});

const json = (method, p, body) =>
  fetch(base + p, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = (p, headers = {}) => fetch(base + p, { headers });

async function mkUser(nickname = '이웃') {
  return (await (await json('POST', '/api/users', { nickname, region: 'Seoul' })).json()).id;
}

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 6, 0xff, 0xd9]);
const THUMB = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 9, 9, 0xff, 0xd9]);
const MP4 = Buffer.from('....ftypisom-fake-video-bytes-0123456789');

function upload({ userId, data = JPEG, mime = 'image/jpeg', name = 'a.jpg', thumb = null, fields = {} }) {
  const fd = new FormData();
  if (userId !== undefined) fd.append('userId', userId);
  for (const [k, v] of Object.entries(fields)) fd.append(k, String(v));
  if (data) fd.append('file', new Blob([data], { type: mime }), name);
  if (thumb) fd.append('thumb', new Blob([thumb], { type: 'image/jpeg' }), 'thumb.jpg');
  return fetch(`${base}/api/community/media`, { method: 'POST', body: fd });
}

const filesInDir = () => (fs.existsSync(mediaDir) ? fs.readdirSync(mediaDir) : []);
const fileOf = (url) => path.join(mediaDir, path.basename(url));

test('사진 업로드 → 원본·썸네일 서빙 (Content-Type, 바이트 일치)', async () => {
  const userId = await mkUser();
  const res = await upload({ userId, thumb: THUMB, fields: { width: 1920, height: 1080 } });
  assert.equal(res.status, 200);
  const m = await res.json();
  assert.ok(m.id);
  assert.equal(m.kind, 'photo');
  assert.equal(m.mime, 'image/jpeg');
  assert.equal(m.width, 1920);
  assert.equal(m.height, 1080);
  assert.equal(m.duration_ms, null);
  assert.match(m.url, /^\/api\/community\/media\/file\/[\w-]+\.jpg$/);
  assert.match(m.thumb_url, /^\/api\/community\/media\/file\/[\w-]+\.jpg$/);

  const f = await get(m.url);
  assert.equal(f.status, 200);
  assert.equal(f.headers.get('content-type'), 'image/jpeg');
  assert.deepEqual(Buffer.from(await f.arrayBuffer()), JPEG);

  const t = await get(m.thumb_url);
  assert.equal(t.status, 200);
  assert.equal(t.headers.get('content-type'), 'image/jpeg');
  assert.deepEqual(Buffer.from(await t.arrayBuffer()), THUMB);
});

test('동영상 업로드 → Range 요청에 206 부분 응답', async () => {
  const userId = await mkUser();
  const m = await (await upload({
    userId, data: MP4, mime: 'video/mp4', name: 'clip.mp4', thumb: THUMB,
    fields: { durationMs: 12345, width: 720, height: 1280 },
  })).json();
  assert.equal(m.kind, 'video');
  assert.equal(m.duration_ms, 12345);
  assert.match(m.url, /\.mp4$/);

  const part = await get(m.url, { Range: 'bytes=0-3' });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get('content-type'), 'video/mp4');
  assert.equal(part.headers.get('content-range'), `bytes 0-3/${MP4.length}`);
  assert.deepEqual(Buffer.from(await part.arrayBuffer()), MP4.subarray(0, 4));

  const full = await get(m.url);
  assert.equal(full.status, 200);
  assert.equal(full.headers.get('accept-ranges'), 'bytes');
});

test('업로드 거부: 형식·파일 없음·사용자 없음 — 받은 파일은 남기지 않음', async () => {
  const userId = await mkUser();
  const before = filesInDir().length;

  const txt = await upload({ userId, data: Buffer.from('hello'), mime: 'text/plain', name: 'a.txt' });
  assert.equal(txt.status, 415);

  const none = await upload({ userId, data: null });
  assert.equal(none.status, 400);

  const noUser = await upload({ userId: undefined, thumb: THUMB });
  assert.equal(noUser.status, 400);
  const badUser = await upload({ userId: 'nobody', thumb: THUMB });
  assert.equal(badUser.status, 400);

  // 실행 파일 이름을 붙여도 확장자는 MIME 으로 정해진다
  const named = await (await upload({ userId, name: 'evil.html' })).json();
  assert.match(named.url, /\.jpg$/);

  assert.equal(filesInDir().length, before + 1);
});

test('사진 크기 상한(20MB) 초과 → 413, 파일 삭제', async () => {
  const userId = await mkUser();
  const before = filesInDir().length;
  const big = Buffer.alloc(20 * 1024 * 1024 + 1, 0xab);
  const res = await upload({ userId, data: big });
  assert.equal(res.status, 413);
  assert.equal(filesInDir().length, before);
});

test('글 작성 mediaIds → 피드·상세에 순서대로 media, 다른 글에 재사용 불가', async () => {
  const userId = await mkUser('사진작가');
  const a = await (await upload({ userId, thumb: THUMB })).json();
  const b = await (await upload({ userId, data: MP4, mime: 'video/mp4', name: 'b.mp4' })).json();

  const created = await (await json('POST', '/api/community/posts', {
    userId, type: 'share', title: '상추 나눔 (사진)', region: 'Seoul', mediaIds: [b.id, a.id],
  })).json();
  assert.ok(created.postId);

  const detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.deepEqual(detail.post.media.map((m) => m.id), [b.id, a.id]);
  assert.deepEqual(detail.post.media.map((m) => m.kind), ['video', 'photo']);
  assert.equal(detail.post.media[0].thumb_url, null);
  assert.ok(detail.post.media[1].thumb_url);

  const feed = await (await get('/api/community/posts?region=Seoul')).json();
  const inFeed = feed.posts.find((p) => p.id === created.postId);
  assert.deepEqual(inFeed.media.map((m) => m.id), [b.id, a.id]);

  // 이미 붙은 미디어로 또 글 작성 → 400, 글은 생기지 않음
  const count = db.prepare('SELECT COUNT(*) AS n FROM community_posts').get().n;
  const again = await json('POST', '/api/community/posts', { userId, type: 'share', title: '재사용', mediaIds: [a.id] });
  assert.equal(again.status, 400);
  assert.equal((await again.json()).error, 'media already attached');
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM community_posts').get().n, count);
});

test('글 작성 mediaIds 검증: 남의 업로드·없는 id·중복·11개 이상 → 400', async () => {
  const owner = await mkUser('주인');
  const other = await mkUser('남');
  const m = await (await upload({ userId: owner })).json();

  const stolen = await json('POST', '/api/community/posts', { userId: other, type: 'sell', title: 'x', mediaIds: [m.id] });
  assert.equal(stolen.status, 400);
  const missing = await json('POST', '/api/community/posts', { userId: owner, type: 'sell', title: 'x', mediaIds: ['nope'] });
  assert.equal(missing.status, 400);
  const dup = await json('POST', '/api/community/posts', { userId: owner, type: 'sell', title: 'x', mediaIds: [m.id, m.id] });
  assert.equal(dup.status, 400);
  const many = await json('POST', '/api/community/posts', {
    userId: owner, type: 'sell', title: 'x', mediaIds: Array.from({ length: 11 }, (_, i) => `id${i}`),
  });
  assert.equal(many.status, 400);
  const notArray = await json('POST', '/api/community/posts', { userId: owner, type: 'sell', title: 'x', mediaIds: m.id });
  assert.equal(notArray.status, 400);

  // 실패한 시도 뒤에도 주인은 그대로 붙일 수 있다
  const ok = await json('POST', '/api/community/posts', { userId: owner, type: 'sell', title: 'ok', mediaIds: [m.id] });
  assert.equal(ok.status, 200);
});

test('기존 호환: mediaIds 없는 글은 media 빈 배열', async () => {
  const userId = await mkUser();
  const created = await (await json('POST', '/api/community/posts', { userId, type: 'buy', title: '고추 구해요' })).json();
  const detail = await (await get(`/api/community/posts/${created.postId}`)).json();
  assert.deepEqual(detail.post.media, []);
});

test('첨부 취소 DELETE: 내 것·붙기 전만, 파일까지 삭제', async () => {
  const owner = await mkUser();
  const other = await mkUser();
  const m = await (await upload({ userId: owner, thumb: THUMB })).json();
  assert.ok(fs.existsSync(fileOf(m.url)));

  assert.equal((await json('DELETE', `/api/community/media/${m.id}`, { userId: other })).status, 403);
  assert.equal((await json('DELETE', `/api/community/media/${m.id}`, { userId: owner })).status, 200);
  assert.equal(fs.existsSync(fileOf(m.url)), false);
  assert.equal(fs.existsSync(fileOf(m.thumb_url)), false);
  assert.equal((await get(m.url)).status, 404);
  assert.equal((await json('DELETE', `/api/community/media/${m.id}`, { userId: owner })).status, 404);

  const attached = await (await upload({ userId: owner })).json();
  await json('POST', '/api/community/posts', { userId: owner, type: 'share', title: 'x', mediaIds: [attached.id] });
  assert.equal((await json('DELETE', `/api/community/media/${attached.id}`, { userId: owner })).status, 409);
});

test('파일 서빙: 등록되지 않은 이름·경로 조작 → 404', async () => {
  assert.equal((await get('/api/community/media/file/nothing.jpg')).status, 404);
  assert.equal((await get('/api/community/media/file/..%2F..%2Fpackage.json')).status, 404);
});

test('sweepOrphanMedia: 24시간 지난 미첨부 업로드만 정리', async () => {
  const userId = await mkUser();
  const stale = await (await upload({ userId, thumb: THUMB })).json();
  const fresh = await (await upload({ userId })).json();
  const attached = await (await upload({ userId })).json();
  await json('POST', '/api/community/posts', { userId, type: 'share', title: 'x', mediaIds: [attached.id] });
  db.prepare("UPDATE community_media SET created_at = datetime('now', '-25 hours') WHERE id IN (?, ?)")
    .run(stale.id, attached.id);

  const n = sweepOrphanMedia();
  assert.equal(n, 1);
  const ids = db.prepare('SELECT id FROM community_media').all().map((r) => r.id);
  assert.ok(!ids.includes(stale.id));
  assert.ok(ids.includes(fresh.id));
  assert.ok(ids.includes(attached.id));
  assert.equal(fs.existsSync(fileOf(stale.url)), false);
  assert.equal(fs.existsSync(fileOf(stale.thumb_url)), false);
  assert.ok(fs.existsSync(fileOf(attached.url)));
});
