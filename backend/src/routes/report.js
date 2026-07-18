import { Router } from 'express';
import db from '../db.js';
const r = Router();

// 슬레이브 7일 요약 리포트 (PRD Phase2 히스토리/리포트)
r.get('/:slaveId', (req, res) => {
  const { slaveId } = req.params;
  const since = "datetime('now','-7 days')";

  const w = db.prepare(`SELECT COUNT(*) AS cnt, COALESCE(SUM(amount_ml),0) AS ml,
      COALESCE(SUM(CASE WHEN source='auto' THEN 1 ELSE 0 END),0) AS auto_cnt
    FROM waterings WHERE slave_id=? AND created_at >= ${since}`).get(slaveId);

  const eventsByType = db.prepare(`SELECT type, COUNT(*) AS cnt
    FROM events WHERE slave_id=? AND created_at >= ${since} GROUP BY type`).all(slaveId);
  const evMap = Object.fromEntries(eventsByType.map((e) => [e.type, e.cnt]));

  const lastWater = db.prepare('SELECT created_at, amount_ml FROM waterings WHERE slave_id=? ORDER BY created_at DESC LIMIT 1').get(slaveId);

  res.json({
    slaveId,
    periodDays: 7,
    watering: { count: w.cnt, totalMl: w.ml, autoCount: w.auto_cnt, manualCount: w.cnt - w.auto_cnt },
    harvestReady: evMap['harvest_ready'] || 0,
    pestDetected: evMap['pest_detected'] || 0,
    anomalies: evMap['anomaly'] || 0,
    eventsByType: evMap,
    lastWatering: lastWater || null,
  });
});

export default r;
