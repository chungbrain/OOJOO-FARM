// 이웃(커뮤니티) 게시물 첨부 미디어 — 사진·동영상 저장/조회/정리
//
// 흐름: master 가 파일을 하나씩 POST /api/community/media 로 올려 mediaId 를 받고,
//       글 작성(POST /api/community/posts)에 mediaIds 를 넘기면 그 순서대로 글에 붙는다.
//       글에 붙지 않은 업로드(작성 취소·실패)는 ORPHAN_TTL_HOURS 뒤 파일과 함께 지운다.
import fs from 'node:fs';
import path from 'node:path';
import db from '../db.js';

export const MEDIA_DIR = path.resolve(process.env.COMMUNITY_MEDIA_DIR || './data/community');
if (!fs.existsSync(MEDIA_DIR)) fs.mkdirSync(MEDIA_DIR, { recursive: true });

export const MAX_MEDIA_PER_POST = 10;
export const MAX_PHOTO_BYTES = 20 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
export const MAX_THUMB_BYTES = 2 * 1024 * 1024;
export const ORPHAN_TTL_HOURS = 24;

// 허용 형식 — 확장자는 원본 파일명이 아니라 MIME 으로 정한다
export const PHOTO_TYPES = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
};
export const VIDEO_TYPES = {
  'video/mp4': '.mp4',
  'video/3gpp': '.3gp',
  'video/webm': '.webm',
  'video/quicktime': '.mov',
  'video/x-matroska': '.mkv',
};

db.exec(`
CREATE TABLE IF NOT EXISTS community_media (
  id TEXT PRIMARY KEY,
  post_id TEXT,               -- 글에 붙기 전에는 NULL
  user_id TEXT NOT NULL,
  kind TEXT NOT NULL,         -- photo | video
  filename TEXT NOT NULL,
  thumb_filename TEXT,
  mime TEXT,
  size INTEGER,
  width INTEGER,
  height INTEGER,
  duration_ms INTEGER,
  sort INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_cmedia_post ON community_media(post_id, sort);
CREATE INDEX IF NOT EXISTS idx_cmedia_user ON community_media(user_id, created_at);
`);

export function mediaKindOf(mime) {
  if (PHOTO_TYPES[mime]) return 'photo';
  if (VIDEO_TYPES[mime]) return 'video';
  return null;
}

export function extensionOf(mime) {
  return PHOTO_TYPES[mime] || VIDEO_TYPES[mime] || null;
}

const fileUrl = (name) => `/api/community/media/file/${name}`;

export function toMedia(row) {
  return {
    id: row.id,
    kind: row.kind,
    url: fileUrl(row.filename),
    thumb_url: row.thumb_filename ? fileUrl(row.thumb_filename) : null,
    mime: row.mime,
    size: row.size,
    width: row.width ?? null,
    height: row.height ?? null,
    duration_ms: row.duration_ms ?? null,
  };
}

/** 여러 글의 첨부를 한 번에 — { postId: [media...] } (sort 순). */
export function mediaForPosts(postIds) {
  const out = new Map();
  if (!postIds.length) return out;
  const marks = postIds.map(() => '?').join(',');
  const rows = db.prepare(
    `SELECT * FROM community_media WHERE post_id IN (${marks}) ORDER BY post_id, sort, created_at`
  ).all(...postIds);
  for (const row of rows) {
    if (!out.has(row.post_id)) out.set(row.post_id, []);
    out.get(row.post_id).push(toMedia(row));
  }
  return out;
}

/**
 * 글 작성 전 검증 — mediaIds 가 모두 이 사용자의 아직 붙지 않은 업로드인지.
 * 문제가 있으면 오류 문자열, 정상이면 null.
 */
export function checkAttachable(userId, mediaIds) {
  if (mediaIds == null) return null;
  if (!Array.isArray(mediaIds)) return 'mediaIds must be an array';
  if (mediaIds.length > MAX_MEDIA_PER_POST) return `at most ${MAX_MEDIA_PER_POST} media per post`;
  if (new Set(mediaIds).size !== mediaIds.length) return 'duplicate mediaIds';
  for (const id of mediaIds) {
    if (typeof id !== 'string' || !id) return 'invalid mediaId';
    const row = db.prepare('SELECT user_id, post_id FROM community_media WHERE id=?').get(id);
    if (!row || row.user_id !== userId) return 'media not found';
    if (row.post_id) return 'media already attached';
  }
  return null;
}

/** 검증을 통과한 mediaIds 를 글에 붙인다 (배열 순서 = 표시 순서). */
export function attachMedia(postId, mediaIds) {
  const stmt = db.prepare('UPDATE community_media SET post_id=?, sort=? WHERE id=? AND post_id IS NULL');
  (mediaIds || []).forEach((id, i) => stmt.run(postId, i, id));
}

/** 업로드 파일 삭제 (없으면 무시). */
export function removeFiles(...names) {
  for (const name of names) {
    if (!name) continue;
    try { fs.rmSync(path.join(MEDIA_DIR, path.basename(name))); } catch { /* 이미 없음 */ }
  }
}

/** 미디어 1건 삭제 — 행 + 원본/썸네일 파일. */
export function deleteMediaRow(row) {
  db.prepare('DELETE FROM community_media WHERE id=?').run(row.id);
  removeFiles(row.filename, row.thumb_filename);
}

/** 글에 붙지 않고 TTL 이 지난 업로드 정리 — 지운 개수 반환. */
export function sweepOrphanMedia(ttlHours = ORPHAN_TTL_HOURS) {
  const rows = db.prepare(
    "SELECT * FROM community_media WHERE post_id IS NULL AND created_at < datetime('now', ?)"
  ).all(`-${ttlHours} hours`);
  for (const row of rows) deleteMediaRow(row);
  return rows.length;
}

/** 파일 서빙용 — 파일명이 원본이든 썸네일이든 등록된 것만 찾아 Content-Type 을 돌려준다. */
export function mimeForFilename(name) {
  const row = db.prepare('SELECT filename, mime FROM community_media WHERE filename=? OR thumb_filename=?').get(name, name);
  if (!row) return null;
  if (row.filename === name) return row.mime || 'application/octet-stream';
  // 썸네일 — 업로드 때 PHOTO_TYPES 의 확장자로 저장했다
  const ext = path.extname(name).toLowerCase();
  return Object.keys(PHOTO_TYPES).find((m) => PHOTO_TYPES[m] === ext) || 'image/jpeg';
}
