import { Router } from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
import { notifyMasterEvent } from './commands.js';

const r = Router();

// 진단 프레임 저장 디렉터리
const DIAGNOSIS_DIR = path.resolve('./data/diagnosis');
if (!fs.existsSync(DIAGNOSIS_DIR)) fs.mkdirSync(DIAGNOSIS_DIR, { recursive: true });

// 진단 세션 테이블 보장
try {
  db.exec(`
    CREATE TABLE IF NOT EXISTS diagnosis_sessions (
      id TEXT PRIMARY KEY,
      slave_id TEXT NOT NULL,
      plant_id TEXT NOT NULL,
      video_id TEXT,
      command_id TEXT,
      model_id TEXT,
      ai_label TEXT NOT NULL,
      ai_confidence REAL,
      human_label TEXT,
      confirmed_by TEXT,
      confirmed_at TEXT,
      created_at TEXT DEFAULT (datetime('now'))
    );
    CREATE INDEX IF NOT EXISTS idx_diagnosis_plant ON diagnosis_sessions(plant_id, created_at);
    CREATE TABLE IF NOT EXISTS diagnosis_frames (
      id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL,
      frame_index INTEGER NOT NULL,
      timestamp_ms INTEGER,
      condition TEXT,
      confidence REAL,
      filename TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_diagnosis_frames ON diagnosis_frames(session_id, frame_index);
  `);
} catch (_) {}

// multer: 메모리 저장 (세션 디렉터리가 핸들러에서 생성되므로)
//   파일 필드명: frames (반복), 최대 8개, 개당 5MB
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { files: 8, fileSize: 5 * 1024 * 1024 },
});

// 슬레이브: 진단 세션 업로드 (multipart/form-data, 파일 필드명: frames 반복)
//   slaveId 는 URL path 에서 읽음 (multer 가 body 를 파싱하기 전에 slaveAuth 가 동작하므로)
r.post('/sessions/:slaveId', slaveAuth, upload.array('frames', 8), (req, res) => {
  const slaveId = req.params.slaveId;
  const { plantId, modelId, aiLabel, aiConfidence, frameCount, videoId, commandId, framesMeta } = req.body || {};
  if (!plantId || !aiLabel) return res.status(400).json({ error: 'plantId, aiLabel required' });
  const files = req.files || [];
  if (files.length === 0) return res.status(400).json({ error: 'frames files required' });

  // framesMeta 안전 파싱 (우리 앱이 보낸 신뢰 가능한 JSON)
  let meta = [];
  try {
    const parsed = JSON.parse(framesMeta || '[]');
    if (Array.isArray(parsed)) meta = parsed;
  } catch (_) {}

  // aiConfidence: multipart 에서 문자열로 도착 → REAL 로 저장
  let conf = null;
  if (aiConfidence != null && aiConfidence !== '') {
    const n = Number(aiConfidence);
    if (!Number.isNaN(n)) conf = n;
  }

  const id = nanoid(12);
  const sessionDir = path.join(DIAGNOSIS_DIR, id);
  fs.mkdirSync(sessionDir, { recursive: true });

  db.prepare(`
    INSERT INTO diagnosis_sessions(id, slave_id, plant_id, video_id, command_id, model_id, ai_label, ai_confidence)
    VALUES(?,?,?,?,?,?,?,?)
  `).run(id, slaveId, plantId, videoId || null, commandId || null, modelId || null, aiLabel, conf);

  const insertFrame = db.prepare(`
    INSERT INTO diagnosis_frames(id, session_id, frame_index, timestamp_ms, condition, confidence, filename)
    VALUES(?,?,?,?,?,?,?)
  `);

  files.forEach((file, i) => {
    const filename = `frame_${i}.jpg`;
    fs.writeFileSync(path.join(sessionDir, filename), file.buffer);
    const m = meta.find((x) => x && x.index === i) || null;
    insertFrame.run(
      nanoid(12),
      id,
      i,
      m && m.timestampMs != null ? m.timestampMs : null,
      m && m.condition != null ? m.condition : null,
      m && m.confidence != null ? m.confidence : null,
      filename
    );
  });

  notifyMasterEvent(slaveId, {
    type: 'diagnosis_ready',
    sessionId: id,
    slaveId,
    plantId,
    aiLabel,
    aiConfidence: conf,
    videoId: videoId || null,
    commandId: commandId || null,
  });

  res.json({ sessionId: id });
});

// 마스터: 식물별 진단 세션 이력 (최신순 20개)
r.get('/plant/:plantId', (req, res) => {
  const rows = db.prepare('SELECT * FROM diagnosis_sessions WHERE plant_id=? ORDER BY created_at DESC LIMIT 20')
    .all(req.params.plantId);
  const sessions = rows.map((row) => {
    const frames = db.prepare('SELECT * FROM diagnosis_frames WHERE session_id=? ORDER BY frame_index ASC')
      .all(row.id)
      .map((f) => ({
        index: f.frame_index,
        timestampMs: f.timestamp_ms,
        condition: f.condition,
        confidence: f.confidence,
        url: `/api/diagnosis/frame/${row.id}/${f.frame_index}`,
      }));
    return {
      id: row.id,
      slaveId: row.slave_id,
      plantId: row.plant_id,
      videoId: row.video_id,
      modelId: row.model_id,
      aiLabel: row.ai_label,
      aiConfidence: row.ai_confidence,
      humanLabel: row.human_label,
      confirmedBy: row.confirmed_by,
      confirmedAt: row.confirmed_at,
      createdAt: row.created_at,
      frames,
    };
  });
  res.json({ sessions });
});

// 마스터: 진단 결과 사람 확정
const VALID_LABELS = ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat'];
r.post('/sessions/:id/confirm', (req, res) => {
  const { humanLabel, confirmedBy } = req.body || {};
  if (!VALID_LABELS.includes(humanLabel)) {
    return res.status(400).json({ error: `humanLabel must be one of: ${VALID_LABELS.join(', ')}` });
  }
  const row = db.prepare('SELECT id FROM diagnosis_sessions WHERE id=?').get(req.params.id);
  if (!row) return res.status(404).json({ error: 'session not found' });
  db.prepare('UPDATE diagnosis_sessions SET human_label=?, confirmed_by=?, confirmed_at=datetime(\'now\') WHERE id=?')
    .run(humanLabel, confirmedBy || null, req.params.id);
  res.json({ ok: true });
});

// 진단 프레임 이미지 서빙
r.get('/frame/:sessionId/:index', (req, res) => {
  const sessionId = path.basename(req.params.sessionId);
  const index = path.basename(req.params.index);
  const file = path.join(DIAGNOSIS_DIR, sessionId, `frame_${index}.jpg`);
  if (!fs.existsSync(file)) return res.status(404).json({ error: 'file not found' });
  res.set({ 'Content-Type': 'image/jpeg', 'Cache-Control': 'public, max-age=86400' });
  fs.createReadStream(file).pipe(res);
});

export default r;
