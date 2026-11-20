import { Router } from 'express';
import db from '../db.js';
const r = Router();

const DEFAULT_POLICY = {
  water_auto: 1,
  fan_auto: 1,
  laser_approval: 1,
  capture_interval: 60,   // PRD 4.2: 기본 1시간, 범위 10분~6시간
  pest_interval: 15,      // PRD 4.2: 해충 감지용 짧은 주기, 기본 15분
  roi_interval: 20,
  region: null,
  // F4 — 카메라 물리 설치 위치 기반 자동 가로/세로 촬영.
  // auto: 디스플레이 회전 감지 / landscape·portrait: 고정 설치 기기 명시 지정
  camera_mount: 'auto',
};

const CAMERA_MOUNTS = new Set(['auto', 'landscape', 'portrait']);

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
    waterAuto, fanAuto, laserApproval, captureInterval, pestInterval, roiInterval, region, cameraMount,
  } = req.body;

  const cur = db.prepare('SELECT * FROM policies WHERE slave_id=?').get(slaveId) || DEFAULT_POLICY;
  const next = {
    water_auto: waterAuto != null ? (waterAuto ? 1 : 0) : cur.water_auto,
    fan_auto: fanAuto != null ? (fanAuto ? 1 : 0) : cur.fan_auto,
    laser_approval: laserApproval != null ? (laserApproval ? 1 : 0) : cur.laser_approval,
    // PRD 4.2: 캡처 주기 10분~6시간 (과거 하한 1분은 PRD 미달)
    capture_interval: captureInterval != null
      ? Math.min(360, Math.max(10, Number(captureInterval))) : cur.capture_interval,
    // PRD 4.2: 해충 짧은 주기 5분~2시간
    pest_interval: pestInterval != null
      ? Math.min(120, Math.max(5, Number(pestInterval))) : (cur.pest_interval ?? 15),
    roi_interval: roiInterval != null
      ? Math.min(3600, Math.max(10, Number(roiInterval))) : cur.roi_interval,
    region: region !== undefined ? region : cur.region,
    camera_mount: cameraMount !== undefined
      ? (CAMERA_MOUNTS.has(cameraMount) ? cameraMount : cur.camera_mount)
      : (cur.camera_mount ?? 'auto'),
  };

  db.prepare(`INSERT INTO policies(slave_id, water_auto, fan_auto, laser_approval, capture_interval, pest_interval, roi_interval, region, camera_mount, updated_at)
    VALUES(?,?,?,?,?,?,?,?,?,datetime('now'))
    ON CONFLICT(slave_id) DO UPDATE SET
      water_auto=excluded.water_auto, fan_auto=excluded.fan_auto,
      laser_approval=excluded.laser_approval, capture_interval=excluded.capture_interval,
      pest_interval=excluded.pest_interval, roi_interval=excluded.roi_interval,
      region=excluded.region, camera_mount=excluded.camera_mount, updated_at=datetime('now')`)
    .run(slaveId, next.water_auto, next.fan_auto, next.laser_approval, next.capture_interval, next.pest_interval, next.roi_interval, next.region, next.camera_mount);

  res.json({ slaveId, ...next });
});

export default r;
