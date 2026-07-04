import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
const r = Router();

const TYPES = ['share', 'sell', 'buy'];

function bumpReputation(userId, delta) {
  db.prepare('INSERT OR IGNORE INTO community_reputation(user_id, score, deals) VALUES(?,0,0)').run(userId);
  db.prepare('UPDATE community_reputation SET score = score + ?, deals = deals + 1 WHERE user_id=?').run(delta, userId);
}

function decorate(rows) {
  return rows.map((p) => {
    const rep = db.prepare('SELECT score, deals FROM community_reputation WHERE user_id=?').get(p.user_id);
    const u = db.prepare('SELECT nickname FROM users WHERE id=?').get(p.user_id);
    return { ...p, author_name: u?.nickname || '이웃', author_score: rep?.score || 0, author_deals: rep?.deals || 0 };
  });
}

// 지역 피드 (type/검색 필터, 차단 사용자 제외)
r.get('/posts', (req, res) => {
  const { region, type, q, viewerId } = req.query;
  const where = [];
  const args = [];
  if (region) { where.push('region = ?'); args.push(region); }
  if (type && TYPES.includes(type)) { where.push('type = ?'); args.push(type); }
  if (q) { where.push('(title LIKE ? OR crop LIKE ? OR description LIKE ?)'); const like = `%${q}%`; args.push(like, like, like); }
  if (viewerId) { where.push('user_id NOT IN (SELECT blocked_id FROM community_blocks WHERE blocker_id = ?)'); args.push(viewerId); }
  let sql = 'SELECT * FROM community_posts';
  if (where.length) sql += ' WHERE ' + where.join(' AND ');
  sql += ' ORDER BY created_at DESC LIMIT 100';
  res.json({ posts: decorate(db.prepare(sql).all(...args)) });
});

// 게시물 상세 + 댓글
r.get('/posts/:id', (req, res) => {
  const post = db.prepare('SELECT * FROM community_posts WHERE id=?').get(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  const comments = db.prepare(`
    SELECT c.*, u.nickname AS author_name
    FROM community_comments c LEFT JOIN users u ON c.user_id=u.id
    WHERE c.post_id=? ORDER BY c.created_at ASC`).all(req.params.id);
  res.json({ post: decorate([post])[0], comments });
});

// 게시물 작성
r.post('/posts', (req, res) => {
  const { userId, type, title, crop, quantity, price, region, description, image } = req.body;
  if (!userId || !type || !title) return res.status(400).json({ error: 'userId, type, title required' });
  if (!TYPES.includes(type)) return res.status(400).json({ error: 'invalid type' });
  const id = nanoid(12);
  db.prepare(`INSERT INTO community_posts(id, user_id, type, title, crop, quantity, price, region, description, image)
    VALUES(?,?,?,?,?,?,?,?,?,?)`)
    .run(id, userId, type, title, crop || null, quantity || null,
      type === 'sell' ? (Number(price) || 0) : null, region || null, description || null, image || null);
  db.prepare('INSERT OR IGNORE INTO community_reputation(user_id, score, deals) VALUES(?,0,0)').run(userId);
  res.json({ postId: id });
});

// 댓글 작성 (이웃과 소통)
r.post('/posts/:id/comments', (req, res) => {
  const { userId, body } = req.body;
  if (!userId || !body) return res.status(400).json({ error: 'userId, body required' });
  const post = db.prepare('SELECT id FROM community_posts WHERE id=?').get(req.params.id);
  if (!post) return res.status(404).json({ error: 'post not found' });
  const id = nanoid(12);
  db.prepare('INSERT INTO community_comments(id, post_id, user_id, body) VALUES(?,?,?,?)').run(id, req.params.id, userId, body);
  res.json({ commentId: id });
});

// 상태 변경: reserved / done. done 시 작성자 평판 +1
r.patch('/posts/:id/status', (req, res) => {
  const { status } = req.body;
  if (!['open', 'reserved', 'done'].includes(status)) return res.status(400).json({ error: 'invalid status' });
  const post = db.prepare('SELECT * FROM community_posts WHERE id=?').get(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  db.prepare('UPDATE community_posts SET status=? WHERE id=?').run(status, req.params.id);
  if (status === 'done' && post.status !== 'done') bumpReputation(post.user_id, 1);
  res.json({ ok: true, status });
});

// 신고
r.post('/report', (req, res) => {
  const { reporterId, postId, targetUserId, reason } = req.body;
  if (!reporterId || (!postId && !targetUserId)) return res.status(400).json({ error: 'reporterId and target required' });
  db.prepare('INSERT INTO community_reports(id, reporter_id, post_id, target_user_id, reason) VALUES(?,?,?,?,?)')
    .run(nanoid(12), reporterId, postId || null, targetUserId || null, reason || null);
  res.json({ ok: true });
});

// 차단 / 차단 해제
r.post('/block', (req, res) => {
  const { blockerId, blockedId } = req.body;
  if (!blockerId || !blockedId) return res.status(400).json({ error: 'blockerId, blockedId required' });
  db.prepare('INSERT OR IGNORE INTO community_blocks(blocker_id, blocked_id) VALUES(?,?)').run(blockerId, blockedId);
  res.json({ ok: true });
});
r.delete('/block', (req, res) => {
  const { blockerId, blockedId } = req.body;
  db.prepare('DELETE FROM community_blocks WHERE blocker_id=? AND blocked_id=?').run(blockerId, blockedId);
  res.json({ ok: true });
});

// 평판 조회
r.get('/reputation/:userId', (req, res) => {
  const rep = db.prepare('SELECT score, deals FROM community_reputation WHERE user_id=?').get(req.params.userId)
    || { score: 0, deals: 0 };
  res.json({ userId: req.params.userId, ...rep });
});

// 내 게시물
r.get('/mine/:userId', (req, res) => {
  const rows = db.prepare('SELECT * FROM community_posts WHERE user_id=? ORDER BY created_at DESC').all(req.params.userId);
  res.json({ posts: decorate(rows) });
});

export default r;
