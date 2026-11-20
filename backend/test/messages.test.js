// DM/쪽지 통합 테스트 (PRD 4.9, Phase 3)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-msg-test-${process.pid}.db`);
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

const get = (p) => fetch(base + p);
const post = (p, b) => fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });

async function mkUser(nick) {
  return (await (await post('/api/users', { nickname: nick, region: 'Seoul' })).json());
}

test('DM: 주고받은 뒤 대화 목록/스레드 확인', async () => {
  const alice = await mkUser('앨리스');
  const bob = await mkUser('밥');

  assert.equal((await post('/api/messages', { senderId: alice.id, recipientId: bob.id, body: '상추 드릴게요' })).status, 200);
  await new Promise((r) => setTimeout(r, 1100)); // created_at 초 단위 분리
  assert.equal((await post('/api/messages', { senderId: bob.id, recipientId: alice.id, body: '고마워요!' })).status, 200);

  // alice 관점 대화 목록
  const conv = await (await get(`/api/messages/conversations?userId=${alice.id}`)).json();
  assert.equal(conv.conversations.length, 1);
  assert.equal(conv.conversations[0].peerId, bob.id);
  assert.equal(conv.conversations[0].lastMessage.body, '고마워요!');
  assert.equal(conv.conversations[0].lastMessage.fromMe, false);
  assert.equal(conv.conversations[0].unread, 1); // bob -> alice 1건 안 읽음

  // 스레드 (시간 순, fromMe 플래그)
  const thread = await (await get(`/api/messages/thread?userId=${alice.id}&peerId=${bob.id}`)).json();
  assert.equal(thread.messages.length, 2);
  assert.equal(thread.messages[0].body, '상추 드릴게요');
  assert.equal(thread.messages[0].fromMe, true);
  assert.equal(thread.messages[1].fromMe, false);

  // bob 쪽에도 같은 스레드 (역방향 fromMe)
  const bobThread = await (await get(`/api/messages/thread?userId=${bob.id}&peerId=${alice.id}`)).json();
  assert.equal(bobThread.messages[0].fromMe, false);
});

test('DM: 읽음 처리 후 안 읽음 0', async () => {
  const alice = await mkUser('앨리스2');
  const bob = await mkUser('밥2');
  await post('/api/messages', { senderId: bob.id, recipientId: alice.id, body: 'hi' });
  await post('/api/messages', { senderId: bob.id, recipientId: alice.id, body: 'hi2' });

  let conv = await (await get(`/api/messages/conversations?userId=${alice.id}`)).json();
  assert.equal(conv.conversations[0].unread, 2);

  const read = await (await post('/api/messages/read', { userId: alice.id, peerId: bob.id })).json();
  assert.equal(read.marked, 2);

  conv = await (await get(`/api/messages/conversations?userId=${alice.id}`)).json();
  assert.equal(conv.conversations[0].unread, 0);

  // 이미 읽은 건 다시 카운트 안 됨
  const read2 = await (await post('/api/messages/read', { userId: alice.id, peerId: bob.id })).json();
  assert.equal(read2.marked, 0);
});

test('DM: 검증 (자기 자신, 빈 body, 2000자 초과 body, 필수 누락)', async () => {
  const alice = await mkUser('앨리스3');
  assert.equal((await post('/api/messages', { senderId: alice.id, recipientId: alice.id, body: 'me' })).status, 400);
  assert.equal((await post('/api/messages', { senderId: alice.id, recipientId: 'other', body: '   ' })).status, 400);
  assert.equal((await post('/api/messages', { senderId: alice.id, recipientId: 'other', body: 'x'.repeat(2001) })).status, 400);
  assert.equal((await post('/api/messages', { senderId: alice.id, body: 'x' })).status, 400);
  assert.equal((await post('/api/messages', { recipientId: 'other', body: 'x' })).status, 400);

  // 빈 대화 목록 (누구랑도 안 주고받음)
  const conv = await (await get(`/api/messages/conversations?userId=${alice.id}`)).json();
  assert.deepEqual(conv.conversations, []);
});
