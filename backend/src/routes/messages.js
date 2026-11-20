import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

// 1:1 쪽지/DM (PRD 4.9 "주변 사용자 댓글/쪽지", Phase 3 채팅).
// 폴링 기반 (FCM 없음 — 결정 로그 참조). 쌍방향 스레드, 그룹 없음.
db.exec(`
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  sender_id TEXT NOT NULL,
  recipient_id TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now')),
  read_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id, created_at);
`);

// 대화 목록 — 상대방별 마지막 메시지 + 안 읽음 수
r.get('/conversations', (req, res) => {
  const { userId } = req.query;
  if (!userId) return res.status(400).json({ error: 'userId required' });

  const peers = db.prepare(`
    SELECT peer_id, MAX(created_at) AS last_at
    FROM (
      SELECT recipient_id AS peer_id, created_at FROM messages WHERE sender_id = ?
      UNION ALL
      SELECT sender_id AS peer_id, created_at FROM messages WHERE recipient_id = ?
    )
    GROUP BY peer_id
    ORDER BY last_at DESC
    LIMIT 50
  `).all(userId, userId);

  const conversations = peers.map((p) => {
    const last = db.prepare(`
      SELECT * FROM messages
      WHERE (sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)
      ORDER BY created_at DESC LIMIT 1
    `).get(userId, p.peer_id, p.peer_id, userId);
    const unread = db.prepare(`
      SELECT COUNT(*) AS n FROM messages
      WHERE sender_id=? AND recipient_id=? AND read_at IS NULL
    `).get(p.peer_id, userId).n;
    return {
      peerId: p.peer_id,
      lastAt: p.last_at,
      unread,
      lastMessage: last
        ? { id: last.id, body: last.body, created_at: last.created_at, fromMe: last.sender_id === userId }
        : null,
    };
  });
  res.json({ conversations });
});

// 스레드 메시지 (최신 100개, 시간 순)
r.get('/thread', (req, res) => {
  const { userId, peerId } = req.query;
  if (!userId || !peerId) return res.status(400).json({ error: 'userId and peerId required' });
  const rows = db.prepare(`
    SELECT * FROM messages
    WHERE (sender_id=? AND recipient_id=?) OR (sender_id=? AND recipient_id=?)
    ORDER BY created_at DESC LIMIT 100
  `).all(userId, peerId, peerId, userId).reverse();
  res.json({
    messages: rows.map((m) => ({ ...m, fromMe: m.sender_id === userId })),
  });
});

// 메시지 보내기
r.post('/', (req, res) => {
  const { senderId, recipientId, body } = req.body || {};
  if (!senderId || !recipientId) return res.status(400).json({ error: 'senderId, recipientId required' });
  if (senderId === recipientId) return res.status(400).json({ error: 'cannot message yourself' });
  if (typeof body !== 'string' || body.trim().length === 0 || body.length > 2000) {
    return res.status(400).json({ error: 'body must be 1..2000 chars' });
  }
  const id = nanoid(12);
  db.prepare('INSERT INTO messages(id, sender_id, recipient_id, body) VALUES(?,?,?,?)')
    .run(id, senderId, recipientId, body.trim());
  res.json({ messageId: id });
});

// 상대방이 보낸 메시지 전부 읽음 처리
r.post('/read', (req, res) => {
  const { userId, peerId } = req.body || {};
  if (!userId || !peerId) return res.status(400).json({ error: 'userId, peerId required' });
  const info = db.prepare(`
    UPDATE messages SET read_at = datetime('now')
    WHERE sender_id=? AND recipient_id=? AND read_at IS NULL
  `).run(peerId, userId);
  res.json({ ok: true, marked: Number(info.changes) });
});

export default r;
