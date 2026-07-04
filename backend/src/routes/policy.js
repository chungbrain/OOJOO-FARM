import { Router } from 'express';
import db from '../db.js';
const r = Router();

const DEFAULT_POLICY = {
  water_auto: 1,
  fan_auto: 1,
  laser_approval: 1,
  capture_interval: 60,
  region: null,
};

// 슬레이브 자율 실행 정책 조회 (없으면 기본값).
// 마스터: 정책 화면 표시 / 슬레이브: 부팅 시 원격 정책 동기화
r.get('/:slaveId', (req, res) => {
  const row = db.prepare('SELECT * FROM policies WHERE slave_id=?').get(req.params.slaveId);
  res.json({ slaveId: req.params.slaveId, ...(row || DEFAULT_POLICY) });
});

// 정책 설정/갱신 (마스터가 원격 지정).
r.put('/:slaveId', (req, res) => {
  const { slaveId } = req.params;
  const {
    waterAuto, fanAuto, laserApproval, captureInterval, region,
  } = req.body;

  const cur = db.prepare('SELECT * FROM policies WHERE slave_id=?').get(slaveId) || DEFAULT_POLICY;
  const next = {
    water_auto: waterAuto != null ? (waterAuto ? 1 : 0) : cur.water_auto,
    fan_auto: fanAuto != null ? (fanAuto ? 1 : 0) : cur.fan_auto,
    laser_approval: laserApproval != null ? (laserApproval ? 1 : 0) : cur.laser_approval,
    capture_interval: captureInterval != null
      ? Math.min(360, Math.max(1, Number(captureInterval))) : cur.capture_interval,
    region: region !== undefined ? region : cur.region,
  };

  db.prepare(`INSERT INTO policies(slave_id, water_auto, fan_auto, laser_approval, capture_interval, region, updated_at)
    VALUES(?,?,?,?,?,?,datetime('now'))
    ON CONFLICT(slave_id) DO UPDATE SET
      water_auto=excluded.water_auto, fan_auto=excluded.fan_auto,
      laser_approval=excluded.laser_approval, capture_interval=excluded.capture_interval,
      region=excluded.region, updated_at=datetime('now')`)
    .run(slaveId, next.water_auto, next.fan_auto, next.laser_approval, next.capture_interval, next.region);

  res.json({ slaveId, ...next });
});

export default r;
