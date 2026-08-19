import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
import { notifyMasterEvent } from './commands.js';

const r = Router();

// 비디오 저장 디렉터리
const VIDEO_DIR = path.resolve('./data/videos');
if (!fs.existsSync(VIDEO_DIR)) fs.mkdirSync(VIDEO_DIR, { recursive: true });

// 컬럼 보장 (기존 DB 호환)
function ensureVideosTable() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS videos (
      id TEXT PRIMARY KEY,
      slave_id TEXT NOT NULL,
      command_id TEXT,
      filename TEXT NOT NULL,
      mime TEXT,
      size INTEGER,
      created_at TEXT DEFAULT (datetime('now'))
    );
    CREATE INDEX IF NOT EXISTS idx_videos_slave ON videos(slave_id, created_at);
  `);
}
ensureVideosTable();

function ensureColumn(table, column, ddl) {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all();
  if (!cols.some((c) => c.name === column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${ddl};`);
  }
}
ensureColumn('videos', 'plant_id', 'plant_id TEXT');
ensureColumn('videos', 'kind', "kind TEXT DEFAULT 'live'");
ensureColumn('videos', 'photo_count', 'photo_count INTEGER');

// multer: 디스크 저장
const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, VIDEO_DIR),
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname) || '.mp4';
      cb(null, `${nanoid(16)}${ext}`);
    },
  }),
  limits: { fileSize: 40 * 1024 * 1024 },
});

function toVideo(row) {
  return {
    videoId: row.id,
    slaveId: row.slave_id,
    commandId: row.command_id,
    plantId: row.plant_id || null,
    kind: row.kind || 'live',
    photo_count: row.photo_count,
    url: `/api/videos/file/${row.filename}`,
    mime: row.mime,
    size: row.size,
    created_at: row.created_at,
  };
}

// 슬레이브: 비디오 업로드 (multipart/form-data, 필드명: video)
//   slaveId 는 URL path 에서 읽음 (multer 가 body 를 파싱하기 전에 slaveAuth 가 동작하므로)
//   body: commandId?
//   세션키 인증
r.post('/upload/:slaveId', slaveAuth, upload.single('video'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'video file required' });
  const { commandId, plantId, kind, photoCount } = req.body || {};
  const slaveId = req.params.slaveId;
  const id = nanoid(12);
  const videoKind = kind || 'live';
  db.prepare('INSERT INTO videos(id, slave_id, command_id, filename, mime, size, plant_id, kind, photo_count) VALUES(?,?,?,?,?,?,?,?,?)')
    .run(
      id,
      slaveId,
      commandId || null,
      req.file.filename,
      req.file.mimetype,
      req.file.size,
      plantId || null,
      videoKind,
      photoCount != null && photoCount !== '' ? Number(photoCount) : null
    );
  const eventType = videoKind === 'growth' ? 'growth_clip_ready' : 'video_ready';
  notifyMasterEvent(slaveId, {
    type: eventType,
    videoId: id,
    slaveId,
    commandId: commandId || null,
    plantId: plantId || null,
    kind: videoKind,
    url: `/api/videos/file/${req.file.filename}`,
    mime: req.file.mimetype,
    size: req.file.size,
  });
  res.json({ videoId: id, url: `/api/videos/file/${req.file.filename}`, kind: videoKind });
});

r.post('/clip-failed/:slaveId', slaveAuth, (req, res) => {
  const { commandId, error, plantId } = req.body || {};
  notifyMasterEvent(req.params.slaveId, {
    type: 'growth_clip_failed',
    slaveId: req.params.slaveId,
    commandId: commandId || null,
    plantId: plantId || null,
    error: error || 'clip failed',
  });
  res.json({ ok: true });
});

// 마스터: 특정 명령(commandId)에 대한 비디오 조회 — 폴링용
//   해당 commandId 로 업로드된 영상이 있으면 반환, 없으면 404
r.get('/by-command/:commandId', (req, res) => {
  const row = db.prepare('SELECT * FROM videos WHERE command_id=? ORDER BY created_at DESC LIMIT 1').get(req.params.commandId);
  if (!row) return res.status(404).json({ error: 'no video yet' });
  res.json(toVideo(row));
});

r.get('/plant/:plantId', (req, res) => {
  const kind = req.query.kind;
  const rows = kind
    ? db.prepare('SELECT * FROM videos WHERE plant_id=? AND kind=? ORDER BY created_at DESC').all(req.params.plantId, kind)
    : db.prepare('SELECT * FROM videos WHERE plant_id=? ORDER BY created_at DESC').all(req.params.plantId);
  res.json({ videos: rows.map(toVideo) });
});

// 마스터: 최신 비디오 조회 (slaveId 기준)
r.get('/latest/:slaveId', (req, res) => {
  const row = db.prepare('SELECT * FROM videos WHERE slave_id=? ORDER BY created_at DESC LIMIT 1').get(req.params.slaveId);
  if (!row) return res.status(404).json({ error: 'no video' });
  res.json(toVideo(row));
});

// 파일 스트리밍 (Range 지원 — 비디오 재생용). /:videoId 보다 먼저 등록해야 함.
r.get('/file/:filename', (req, res) => {
  const file = path.join(VIDEO_DIR, path.basename(req.params.filename));
  if (!fs.existsSync(file)) return res.status(404).json({ error: 'file not found' });
  const stat = fs.statSync(file);
  const range = req.headers.range;
  if (range) {
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
    res.status(206).set({
      'Content-Range': `bytes ${start}-${end}/${stat.size}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': end - start + 1,
      'Content-Type': 'video/mp4',
    });
    fs.createReadStream(file, { start, end }).pipe(res);
  } else {
    res.set({ 'Content-Length': stat.size, 'Content-Type': 'video/mp4' });
    fs.createReadStream(file).pipe(res);
  }
});

r.get('/:videoId', (req, res) => {
  const row = db.prepare('SELECT * FROM videos WHERE id=?').get(req.params.videoId);
  if (!row) return res.status(404).json({ error: 'not found' });
  res.json(toVideo(row));
});

export default r;
