import { Router } from 'express';
import multer from 'multer';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { validCoord } from '../lib/validate.js';
import {
  MEDIA_DIR, MAX_PHOTO_BYTES, MAX_VIDEO_BYTES, MAX_THUMB_BYTES, PHOTO_TYPES,
  mediaKindOf, extensionOf, toMedia, mediaForPosts, checkAttachable, attachMedia,
  removeFiles, deleteMediaRow, mimeForFilename,
} from '../lib/communityMedia.js';
const r = Router();

const TYPES = ['share', 'sell', 'buy'];

// lat/lon 컬럼 보장
try {
  db.exec('ALTER TABLE community_posts ADD COLUMN lat REAL');
} catch (_) {}
try {
  db.exec('ALTER TABLE community_posts ADD COLUMN lon REAL');
} catch (_) {}
// 식물카드 "나눔/판매" — 원본 식물 + 성장스토리 동영상 연결
try {
  db.exec('ALTER TABLE community_posts ADD COLUMN plant_id TEXT');
} catch (_) {}
try {
  db.exec('ALTER TABLE community_posts ADD COLUMN video_id TEXT');
} catch (_) {}

// Haversine 거리 (m)
function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371000;
  const toRad = (d) => d * Math.PI / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function bumpReputation(userId, delta) {
  db.prepare('INSERT OR IGNORE INTO community_reputation(user_id, score, deals) VALUES(?,0,0)').run(userId);
  db.prepare('UPDATE community_reputation SET score = score + ?, deals = deals + 1 WHERE user_id=?').run(delta, userId);
}

function decorate(rows) {
  // 첨부 사진·동영상 — 글 목록 전체를 쿼리 한 번으로
  const media = mediaForPosts(rows.map((p) => p.id));
  return rows.map((p) => {
    // PRD 6.7 — 정밀 좌표 비공개: 응답에서 lat/lon 을 뺀다 (지역은 region 필드로)
    const { lat: _lat, lon: _lon, ...rest } = p;
    const rep = db.prepare('SELECT score, deals FROM community_reputation WHERE user_id=?').get(p.user_id);
    const u = db.prepare('SELECT nickname FROM users WHERE id=?').get(p.user_id);
    // 식물카드 게시물 — 원본 식물 정보 + 성장스토리 동영상 URL
    let plant_name = null, plant_species = null, video_url = null;
    if (p.plant_id) {
      const pl = db.prepare('SELECT name, species FROM plants WHERE id=?').get(p.plant_id);
      plant_name = pl ? pl.name : null;
      plant_species = pl ? (pl.species || null) : null;
    }
    if (p.video_id) {
      const v = db.prepare('SELECT filename FROM videos WHERE id=?').get(p.video_id);
      if (v) video_url = `/api/videos/file/${v.filename}`;
    }
    return {
      ...rest, author_name: u?.nickname || '이웃', author_score: rep?.score || 0, author_deals: rep?.deals || 0,
      plant_name, plant_species, video_url, media: media.get(p.id) || [],
    };
  });
}

// 지역 피드 — 위도/경도 기반 반경 필터 (기본 5km, PRD 4.9) + type/검색/차단
r.get('/posts', (req, res) => {
  const { region, type, q, viewerId, lat, lon, radius } = req.query;
  const userLat = lat ? parseFloat(lat) : null;
  const userLon = lon ? parseFloat(lon) : null;
  let maxDist = 5000; // 기본 5km
  if (radius != null) {
    maxDist = parseFloat(radius);
    if (!Number.isFinite(maxDist) || maxDist <= 0 || maxDist > 50000) {
      return res.status(400).json({ error: 'radius must be 1..50000 (m)' });
    }
  }

  let sql = 'SELECT * FROM community_posts';
  const where = [];
  const args = [];

  if (region) { where.push('region = ?'); args.push(region); }
  if (type && TYPES.includes(type)) { where.push('type = ?'); args.push(type); }
  if (q) { where.push('(title LIKE ? OR crop LIKE ? OR description LIKE ?)'); const like = `%${q}%`; args.push(like, like, like); }
  if (viewerId) { where.push('user_id NOT IN (SELECT blocked_id FROM community_blocks WHERE blocker_id = ?)'); args.push(viewerId); }

  if (where.length) sql += ' WHERE ' + where.join(' AND ');
  sql += ' ORDER BY created_at DESC LIMIT 100';

  let rows = db.prepare(sql).all(...args);

  // 반경 필터 (lat/lon이 있는 경우만) — 반경 밖 글은 region 매칭으로도 안 보임
  if (userLat != null && userLon != null) {
    rows = rows.filter(p => {
      if (p.lat == null || p.lon == null) return false; // 위치 정보 없는 글 제외
      return haversine(userLat, userLon, p.lat, p.lon) <= maxDist;
    });
  }

  res.json({ posts: decorate(rows) });
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

// 게시물 작성 (lat/lon + 식물카드: plantId/videoId + 직접 올린 사진·동영상: mediaIds)
r.post('/posts', (req, res) => {
  const { userId, type, title, crop, quantity, price, region, description, image, lat, lon, plantId, videoId, mediaIds } = req.body;
  if (!userId || !type || !title) return res.status(400).json({ error: 'userId, type, title required' });
  if (!TYPES.includes(type)) return res.status(400).json({ error: 'invalid type' });
  const coordErr = validCoord(lat, lon);
  if (coordErr) return res.status(400).json({ error: coordErr });
  // 첨부 — 이 사용자가 POST /media 로 올렸고 아직 다른 글에 붙지 않은 것만
  const mediaErr = checkAttachable(userId, mediaIds);
  if (mediaErr) return res.status(400).json({ error: mediaErr });
  // 식물카드 — 연결 대상이 실제로 존재해야 한다
  let plantRow = null, videoRow = null;
  if (plantId) {
    plantRow = db.prepare('SELECT id FROM plants WHERE id=?').get(plantId);
    if (!plantRow) return res.status(400).json({ error: 'plant not found' });
  }
  if (videoId) {
    videoRow = db.prepare('SELECT id FROM videos WHERE id=?').get(videoId);
    if (!videoRow) return res.status(400).json({ error: 'video not found' });
  }
  const id = nanoid(12);
  db.prepare(`INSERT INTO community_posts(id, user_id, type, title, crop, quantity, price, region, description, image, lat, lon, plant_id, video_id)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
    .run(id, userId, type, title, crop || null, quantity || null,
      type === 'sell' ? (Number(price) || 0) : null, region || null, description || null, image || null,
      lat != null ? Number(lat) : null, lon != null ? Number(lon) : null,
      plantRow ? plantId : null, videoRow ? videoId : null);
  attachMedia(id, mediaIds);
  db.prepare('INSERT OR IGNORE INTO community_reputation(user_id, score, deals) VALUES(?,0,0)').run(userId);
  res.json({ postId: id });
});

// ── 글 첨부 사진·동영상 ──────────────────────────────────────────────
// 파일 하나씩 업로드 → mediaId. 글 작성 때 mediaIds 로 붙인다 (안 붙은 건 24시간 뒤 정리).
const mediaUpload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, MEDIA_DIR),
    filename: (_req, file, cb) => cb(null, `${nanoid(16)}${extensionOf(file.mimetype) || '.bin'}`),
  }),
  limits: { fileSize: MAX_VIDEO_BYTES, files: 2, fields: 10 },
  fileFilter: (req, file, cb) => {
    // file: 사진/동영상, thumb: 미리보기 이미지 (동영상 포스터 · 사진 축소본)
    const ok = file.fieldname === 'thumb' ? Boolean(PHOTO_TYPES[file.mimetype]) : Boolean(mediaKindOf(file.mimetype));
    if (!ok) (req.rejectedFields ||= []).push(file.fieldname);
    cb(null, ok);
  },
}).fields([{ name: 'file', maxCount: 1 }, { name: 'thumb', maxCount: 1 }]);

function receiveMedia(req, res, next) {
  mediaUpload(req, res, (err) => {
    if (!err) return next();
    if (err instanceof multer.MulterError) {
      // multer 가 받다 만 파일은 스스로 지운다
      if (err.code === 'LIMIT_FILE_SIZE') return res.status(413).json({ error: 'file too large' });
      return res.status(400).json({ error: err.message });
    }
    next(err);
  });
}

function posInt(v, max) {
  const n = Number(v);
  return Number.isInteger(n) && n > 0 && n <= max ? n : null;
}

r.post('/media', receiveMedia, (req, res) => {
  const file = req.files?.file?.[0] || null;
  let thumb = req.files?.thumb?.[0] || null;
  const discard = () => removeFiles(file?.filename, thumb?.filename);
  if (!file) {
    discard();
    if (req.rejectedFields?.includes('file')) return res.status(415).json({ error: 'unsupported media type' });
    return res.status(400).json({ error: 'file required' });
  }
  const { userId, width, height, durationMs } = req.body || {};
  if (!userId || !db.prepare('SELECT id FROM users WHERE id=?').get(userId)) {
    discard();
    return res.status(400).json({ error: 'valid userId required' });
  }
  const kind = mediaKindOf(file.mimetype);
  if (kind === 'photo' && file.size > MAX_PHOTO_BYTES) {
    discard();
    return res.status(413).json({ error: 'file too large' });
  }
  // 미리보기는 선택 — 너무 크면 버리고 원본만 받는다
  if (thumb && thumb.size > MAX_THUMB_BYTES) { removeFiles(thumb.filename); thumb = null; }
  const id = nanoid(12);
  db.prepare(`INSERT INTO community_media(id, user_id, kind, filename, thumb_filename, mime, size, width, height, duration_ms)
    VALUES(?,?,?,?,?,?,?,?,?,?)`)
    .run(id, userId, kind, file.filename, thumb ? thumb.filename : null, file.mimetype, file.size,
      posInt(width, 20000), posInt(height, 20000), kind === 'video' ? posInt(durationMs, 24 * 3600 * 1000) : null);
  res.json(toMedia(db.prepare('SELECT * FROM community_media WHERE id=?').get(id)));
});

// 파일 서빙 — Range 지원(동영상 탐색), 등록된 파일만
r.get('/media/file/:filename', (req, res) => {
  const name = path.basename(req.params.filename);
  const mime = mimeForFilename(name);
  if (!mime) return res.status(404).json({ error: 'file not found' });
  res.type(mime);
  res.sendFile(path.join(MEDIA_DIR, name), { maxAge: '1d', dotfiles: 'allow' }, (err) => {
    if (err && !res.headersSent) res.status(err.status || err.statusCode || 404).json({ error: 'file not found' });
  });
});

// 작성 중 첨부 취소 — 아직 글에 붙지 않은 내 업로드만
r.delete('/media/:id', (req, res) => {
  const { userId } = req.body || {};
  const row = db.prepare('SELECT * FROM community_media WHERE id=?').get(req.params.id);
  if (!row) return res.status(404).json({ error: 'not found' });
  if (!userId || row.user_id !== userId) return res.status(403).json({ error: 'not yours' });
  if (row.post_id) return res.status(409).json({ error: 'media already attached' });
  deleteMediaRow(row);
  res.json({ ok: true });
});

// 성장스토리 동영상 첨부 — 게시물은 즉시 생성, 동영상은 준비되면 붙인다
r.patch('/posts/:id/video', (req, res) => {
  const { videoId } = req.body;
  const post = db.prepare('SELECT id FROM community_posts WHERE id=?').get(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  if (videoId) {
    const v = db.prepare('SELECT id FROM videos WHERE id=?').get(videoId);
    if (!v) return res.status(400).json({ error: 'video not found' });
  }
  db.prepare('UPDATE community_posts SET video_id=? WHERE id=?').run(videoId || null, req.params.id);
  res.json({ ok: true });
});

// 댓글 작성
r.post('/posts/:id/comments', (req, res) => {
  const { userId, body } = req.body;
  if (!userId || !body) return res.status(400).json({ error: 'userId, body required' });
  const post = db.prepare('SELECT id FROM community_posts WHERE id=?').get(req.params.id);
  if (!post) return res.status(404).json({ error: 'post not found' });
  const id = nanoid(12);
  db.prepare('INSERT INTO community_comments(id, post_id, user_id, body) VALUES(?,?,?,?)').run(id, req.params.id, userId, body);
  res.json({ commentId: id });
});

// 상태 변경
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
