import { Router } from 'express';
import db from '../db.js';
const r = Router();

// 마스터: 알림 센터 — 사용자의 모든 Farmer 이벤트를 집계 (수확/해충/관수/이상 등)
// 슬레이브명·식물명을 조인해 사람이 읽기 쉬운 형태로 반환한다. (PRD 4.6 알림 센터)
r.get('/:userId', (req, res) => {
  const rows = db.prepare(`
    SELECT e.id, e.type, e.payload, e.created_at,
           e.slave_id, s.name AS slave_name,
           e.plant_id, p.name AS plant_name
    FROM events e
    JOIN slaves s ON e.slave_id = s.id
    LEFT JOIN plants p ON e.plant_id = p.id
    WHERE s.user_id = ?
    ORDER BY e.created_at DESC
    LIMIT 100
  `).all(req.params.userId);
  res.json({ notifications: rows });
});

export default r;
