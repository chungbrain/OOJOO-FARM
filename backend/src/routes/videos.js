import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';

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

// multer: 디스크 저장
const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, VIDEO_DIR),
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname) || '.mp4';
      cb(null, `${nanoid(16)}${ext}`);
    },
  }),
  limits: { fileSize: 30 * 1024 * 1024 }, // 30MB 제한 (3초 영상)
});

// 슬레이브: 비디오 업로드 (multipart/form-data, 필드명: video)
//   body: slaveId, commandId?
//   세션키 인증
r.post('/upload', slaveAuth, upload.single('video'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'video file required' });
  const { slaveId, commandId } = req.body;
  if (!slaveId) {
    fs.unlink(req.file.path, () => {});
    return res.status(400).json({ error: 'slaveId required' });
  }
  const id = nanoid(12);
  db.prepare('INSERT INTO videos(id, slave_id, command_id, filename, mime, size) VALUES(?,?,?,?,?,?)')
    .run(id, slaveId, commandId || null, req.file.filename, req.file.mimetype, req.file.size);
  res.json({ videoId: id, url: `/api/videos/file/${req.file.filename}` });
});

// 마스터: 최신 비디오 조회 (slaveId 기준)
r.get('/latest/:slaveId', (req, res) => {
  const row = db.prepare('SELECT * FROM videos WHERE slave_id=? ORDER BY created_at DESC LIMIT 1').get(req.params.slaveId);
  if (!row) return res.status(404).json({ error: 'no video' });
  res.json({
    videoId: row.id,
    slaveId: row.slave_id,
    commandId: row.command_id,
    url: `/api/videos/file/${row.filename}`,
    mime: row.mime,
    size: row.size,
    created_at: row.created_at,
  });
});

// 마스터: 특정 비디오 메타 조회
r.get('/:videoId', (req, res) => {
  const row = db.prepare('SELECT * FROM videos WHERE id=?').get(req.params.videoId);
  if (!row) return res.status(404).json({ error: 'not found' });
  res.json({
    videoId: row.id,
    slaveId: row.slave_id,
    commandId: row.command_id,
    url: `/api/videos/file/${row.filename}`,
    mime: row.mime,
    size: row.size,
    created_at: row.created_at,
  });
});

// 파일 스트리밍 (Range 지원 — 비디오 재생용)
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

export default r;
