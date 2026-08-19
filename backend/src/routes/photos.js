import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';

const r = Router();

const PHOTO_DIR = path.resolve('./data/photos');
if (!fs.existsSync(PHOTO_DIR)) fs.mkdirSync(PHOTO_DIR, { recursive: true });

db.exec(`
CREATE TABLE IF NOT EXISTS plant_photos (
  id TEXT PRIMARY KEY,
  slave_id TEXT,
  plant_id TEXT,
  filename TEXT NOT NULL,
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

const upload = multer({
  storage: multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, PHOTO_DIR),
    filename: (_req, file, cb) => {
      const ext = path.extname(file.originalname) || '.jpg';
      cb(null, `${nanoid(16)}${ext}`);
    },
  }),
  limits: { fileSize: 8 * 1024 * 1024 },
});

function toPhoto(row) {
  return {
    id: row.id,
    slave_id: row.slave_id,
    plant_id: row.plant_id,
    url: `/api/photos/file/${row.filename}`,
    mime: row.mime,
    size: row.size,
    taken_at: row.taken_at,
    location: row.location,
    lat: row.lat,
    lon: row.lon,
    created_at: row.created_at,
  };
}

r.post('/upload/:slaveId', slaveAuth, upload.single('photo'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'photo file required' });
  const { plantId, takenAt, location, lat, lon } = req.body || {};
  if (!plantId) return res.status(400).json({ error: 'plantId required' });
  const id = nanoid(12);
  db.prepare(`
    INSERT INTO plant_photos(id, slave_id, plant_id, filename, mime, size, taken_at, location, lat, lon)
    VALUES(?,?,?,?,?,?,?,?,?,?)
  `).run(
    id,
    req.params.slaveId,
    plantId,
    req.file.filename,
    req.file.mimetype || 'image/jpeg',
    req.file.size,
    takenAt || null,
    location || null,
    lat != null && lat !== '' ? Number(lat) : null,
    lon != null && lon !== '' ? Number(lon) : null
  );
  const row = db.prepare('SELECT * FROM plant_photos WHERE id=?').get(id);
  res.json({ photoId: id, ...toPhoto(row) });
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

r.get('/file/:filename', (req, res) => {
  const file = path.join(PHOTO_DIR, path.basename(req.params.filename));
  if (!fs.existsSync(file)) return res.status(404).json({ error: 'file not found' });
  res.set({ 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=86400' });
  fs.createReadStream(file).pipe(res);
});

export default r;
