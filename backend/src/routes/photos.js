import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
import { householdMemberIds, inSql } from '../lib/household.js';
import { notifyMasterEvent } from './commands.js';

const r = Router();

const PHOTO_DIR = path.resolve('./data/photos');
if (!fs.existsSync(PHOTO_DIR)) fs.mkdirSync(PHOTO_DIR, { recursive: true });

db.exec(`
CREATE TABLE IF NOT EXISTS plant_photos (
  id TEXT PRIMARY KEY,
  slave_id TEXT,
  plant_id TEXT,
  filename TEXT NOT NULL,
  thumb_filename TEXT,
  mime TEXT,
  size INTEGER,
  taken_at TEXT,
  location TEXT,
  lat REAL,
  lon REAL,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_photos_plant ON plant_photos(plant_id, taken_at);
CREATE INDEX IF NOT EXISTS idx_photos_slave ON plant_photos(slave_id, created_at);
`);
// 기존 DB 호환 — 썸네일 컬럼 추가 (master 사진첩 그리드용)
{
  const cols = db.prepare('PRAGMA table_info(plant_photos)').all();
  if (!cols.some((c) => c.name === 'thumb_filename')) {
    db.exec('ALTER TABLE plant_photos ADD COLUMN thumb_filename TEXT;');
  }
}

const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, PHOTO_DIR),
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname) || '.jpg';
      cb(null, `${nanoid(16)}${ext}`);
    },
  }),
  // 고해상도 촬영 기기 대비 여유 있는 상한 (slave는 업로드 전 1920px 축소본 전송)
  limits: { fileSize: 25 * 1024 * 1024 },
});

function toPhoto(row) {
  return {
    id: row.id,
    slave_id: row.slave_id,
    plant_id: row.plant_id,
    url: `/api/photos/file/${row.filename}`,
    thumb_url: row.thumb_filename ? `/api/photos/file/${row.thumb_filename}` : null,
    mime: row.mime,
    size: row.size,
    taken_at: row.taken_at,
    location: row.location,
    lat: row.lat,
    lon: row.lon,
    created_at: row.created_at,
  };
}

// photo(필수) + thumb(선택, 480px — master 사진첩 그리드용).
// fields()라서 기존 슬레이브( photo 단일 필드) 호환 유지.
const uploadFields = upload.fields([
  { name: 'photo', maxCount: 1 },
  { name: 'thumb', maxCount: 1 },
]);

r.post('/upload/:slaveId', slaveAuth, uploadFields, (req, res) => {
  const files = (req.files && req.files.photo) || [];
  const photoFile = files[0];
  if (!photoFile) return res.status(400).json({ error: 'photo file required' });
  const thumbFile = (req.files && req.files.thumb && req.files.thumb[0]) || null;
  const { plantId, takenAt, location, lat, lon, commandId } = req.body || {};
  if (!plantId) return res.status(400).json({ error: 'plantId required' });
  const id = nanoid(12);
  db.prepare(`
    INSERT INTO plant_photos(id, slave_id, plant_id, filename, thumb_filename, mime, size, taken_at, location, lat, lon)
    VALUES(?,?,?,?,?,?,?,?,?,?,?)
  `).run(
    id,
    req.params.slaveId,
    plantId,
    photoFile.filename,
    thumbFile ? thumbFile.filename : null,
    photoFile.mimetype || 'image/jpeg',
    photoFile.size,
    takenAt || null,
    location || null,
    lat != null && lat !== '' ? Number(lat) : null,
    lon != null && lon !== '' ? Number(lon) : null
  );
  const row = db.prepare('SELECT * FROM plant_photos WHERE id=?').get(id);
  const photo = toPhoto(row);
  // SSE: 연결된 Master에게 즉시 사진 준비 완료 알림 (LiveCamera 등 실시간 UI)
  notifyMasterEvent(req.params.slaveId, {
    type: 'photo_ready',
    photoId: photo.id,
    slaveId: req.params.slaveId,
    plantId: photo.plant_id,
    url: photo.url,
    taken_at: photo.taken_at,
    created_at: photo.created_at,
    commandId: (req.body && req.body.commandId) || null,
  });
  res.json({ photoId: id, ...photo });
});

r.get('/plant/:plantId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plant_photos WHERE plant_id=? ORDER BY COALESCE(taken_at, created_at) ASC')
    .all(req.params.plantId);
  res.json({ photos: rows.map(toPhoto) });
});

r.get('/slave/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plant_photos WHERE slave_id=? ORDER BY created_at DESC LIMIT 80')
    .all(req.params.slaveId);
  res.json({ photos: rows.map(toPhoto) });
});

// 마스터 사진첩 — 사용자(가구) 전체의 farmer→식물 사진.
// 이름 조인까지 여기서 해주고, 그룹핑은 클라이언트에서 한다.
r.get('/user/:userId', (req, res) => {
  const ids = householdMemberIds(req.params.userId);
  const rows = db.prepare(`
    SELECT pp.*, p.name AS plant_name, s.name AS slave_name
    FROM plant_photos pp
    LEFT JOIN plants p ON p.id = pp.plant_id
    LEFT JOIN slaves s ON s.id = pp.slave_id
    WHERE pp.slave_id IN (SELECT id FROM slaves WHERE user_id IN (${inSql(ids)}))
    ORDER BY pp.created_at DESC
    LIMIT 300
  `).all(...ids);
  res.json({
    photos: rows.map((row) => ({
      ...toPhoto(row),
      plant_name: row.plant_name,
      slave_name: row.slave_name,
    })),
  });
});

r.get('/file/:filename', (req, res) => {
  const file = path.join(PHOTO_DIR, path.basename(req.params.filename));
  if (!fs.existsSync(file)) return res.status(404).json({ error: 'file not found' });
  // 업로드 시 기록된 mime 으로 서빙 (PNG 시뮬레이션 사진 등 대응)
  const row = db.prepare('SELECT mime FROM plant_photos WHERE filename=?').get(path.basename(req.params.filename));
  res.set({ 'Content-Type': (row && row.mime) || 'image/jpeg', 'Cache-Control': 'public, max-age=86400' });
  fs.createReadStream(file).pipe(res);
});

export default r;
