import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
const r = Router();

// 분석 결과 테이블 보장
try {
  db.exec(`
    CREATE TABLE IF NOT EXISTS plant_analysis (
      id TEXT PRIMARY KEY,
      slave_id TEXT NOT NULL,
      plant_id TEXT NOT NULL,
      analysis_json TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now'))
    );
    CREATE INDEX IF NOT EXISTS idx_analysis_plant ON plant_analysis(plant_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_analysis_slave ON plant_analysis(slave_id, created_at);
  `);
} catch (_) {}

// 슬레이브: 분석 결과 업로드 (세션 단위, 10분마다)
r.post('/report', slaveAuth, (req, res) => {
  const { slaveId, plantId, analysis } = req.body;
  if (!slaveId || !plantId || !analysis) return res.status(400).json({ error: 'slaveId, plantId, analysis required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO plant_analysis(id, slave_id, plant_id, analysis_json) VALUES(?,?,?,?)')
    .run(id, slaveId, plantId, JSON.stringify(analysis));
  res.json({ analysisId: id });
});

// 마스터: 식물별 최신 분석 조회
r.get('/latest/:plantId', (req, res) => {
  const row = db.prepare('SELECT * FROM plant_analysis WHERE plant_id=? ORDER BY created_at DESC LIMIT 1').get(req.params.plantId);
  if (!row) return res.status(404).json({ error: 'no analysis yet' });
  res.json({
    id: row.id,
    slaveId: row.slave_id,
    plantId: row.plant_id,
    analysis: JSON.parse(row.analysis_json),
    createdAt: row.created_at,
  });
});

// 마스터: 식물별 분석 이력 조회
r.get('/history/:plantId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plant_analysis WHERE plant_id=? ORDER BY created_at DESC LIMIT 20').all(req.params.plantId);
  res.json({ analyses: rows.map(row => ({
    id: row.id,
    analysis: JSON.parse(row.analysis_json),
    createdAt: row.created_at,
  })) });
});

// 마스터: 슬레이브별 최신 분석 조회
r.get('/slave/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM plant_analysis WHERE slave_id=? ORDER BY created_at DESC LIMIT 10').all(req.params.slaveId);
  res.json({ analyses: rows.map(row => ({
    id: row.id,
    plantId: row.plant_id,
    analysis: JSON.parse(row.analysis_json),
    createdAt: row.created_at,
  })) });
});

export default r;
