// 지역 커뮤니티 통합 테스트
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-comm-${process.pid}.db`);
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

const get = (p) => fetch(base + p);
const post = (p, b) => fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
const patch = (p, b) => fetch(base + p, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });

async function mkUser(nick, region) { return (await (await post('/api/users', { nickname: nick, region })).json()); }

test('나눔/판매/구입 작성 + 지역·타입 피드', async () => {
  const alice = await mkUser('앨리스', 'Seoul');
  await post('/api/community/posts', { userId: alice.id, type: 'share', title: '상추 나눔해요', crop: '상추', quantity: '한 봉지', region: 'Seoul' });
  await post('/api/community/posts', { userId: alice.id, type: 'sell', title: '방울토마토 팝니다', crop: '토마토', price: 5000, region: 'Seoul' });
  await post('/api/community/posts', { userId: alice.id, type: 'buy', title: '바질 모종 구해요', crop: '바질', region: 'Busan' });

  const seoul = await (await get('/api/community/posts?region=Seoul')).json();
  assert.equal(seoul.posts.length, 2);
  assert.ok(seoul.posts.every((p) => p.region === 'Seoul'));

  const sells = await (await get('/api/community/posts?region=Seoul&type=sell')).json();
  assert.equal(sells.posts.length, 1);
  assert.equal(sells.posts[0].price, 5000);
  assert.equal(sells.posts[0].author_name, '앨리스');
});

test('댓글 작성 + 상세 조회', async () => {
  const u = await mkUser('밥', 'Seoul');
  const pid = (await (await post('/api/community/posts', { userId: u.id, type: 'share', title: '무 나눔', region: 'Seoul' })).json()).postId;
  const other = await mkUser('캐럴', 'Seoul');
  await post(`/api/community/posts/${pid}/comments`, { userId: other.id, body: '저 받고 싶어요!' });

  const detail = await (await get(`/api/community/posts/${pid}`)).json();
  assert.equal(detail.comments.length, 1);
  assert.equal(detail.comments[0].author_name, '캐럴');
});

test('거래 완료 시 작성자 평판 상승', async () => {
  const u = await mkUser('대니', 'Seoul');
  const pid = (await (await post('/api/community/posts', { userId: u.id, type: 'sell', title: '오이 팝니다', price: 3000, region: 'Seoul' })).json()).postId;

  await patch(`/api/community/posts/${pid}/status`, { status: 'reserved' });
  await patch(`/api/community/posts/${pid}/status`, { status: 'done' });

  const rep = await (await get(`/api/community/reputation/${u.id}`)).json();
  assert.equal(rep.deals, 1);
  assert.ok(rep.score >= 1);
});

test('차단 시 해당 사용자 게시물이 피드에서 제외', async () => {
  const viewer = await mkUser('이브', 'Daejeon');
  const spammer = await mkUser('스팸', 'Daejeon');
  await post('/api/community/posts', { userId: spammer.id, type: 'sell', title: '광고광고', region: 'Daejeon' });

  const before = await (await get(`/api/community/posts?region=Daejeon&viewerId=${viewer.id}`)).json();
  assert.equal(before.posts.length, 1);

  await post('/api/community/block', { blockerId: viewer.id, blockedId: spammer.id });
  const after = await (await get(`/api/community/posts?region=Daejeon&viewerId=${viewer.id}`)).json();
  assert.equal(after.posts.length, 0);
});

test('신고 접수', async () => {
  const u = await mkUser('프랭크', 'Seoul');
  const r = await post('/api/community/report', { reporterId: u.id, postId: 'somepost', reason: '스팸' });
  assert.equal(r.status, 200);
});
