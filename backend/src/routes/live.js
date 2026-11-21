import { Router } from 'express';
import multer from 'multer';
import { slaveAuth } from '../middleware/auth.js';

const r = Router();

// 실시간 보기 릴레이 — Farmer가 3초마다 최신 프레임을 push하고
// Master가 3초마다 pull해 화면을 갱신한다 (LAN 직결 MJPEG 대신 서버 경유).
//
// 프레임은 메모리에 slaveId별 "최신 1장"만 유지한다:
// - 라이브 화면은 항상 최신 프레임만 필요 (히스토리 불필요)
// - 디스크 I/O 없이 즉시 응답 (폴링 주기 3초에 맞춤)
const frames = new Map(); // slaveId -> { jpeg: Buffer, at: epochMs }

// 이보다 오래된 프레임은 만료 — Farmer가 push를 멈추면
// 마스터는 404로 "신호 없음"을 감지한다.
const FRAME_TTL_MS = 12_000;

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 4 * 1024 * 1024 },
});

// multer 오류(잘못된 필드명/크기 초과 등)를 500이 아닌 400으로 응답.
const uploadFrame = (req, res, next) => {
  upload.single('frame')(req, res, (err) => {
    if (err) return res.status(400).json({ error: err.message });
    next();
  });
};

// Farmer: 최신 라이브 프레임 업로드 (multipart 필드: frame, 세션키 인증)
r.post('/:slaveId/frame', slaveAuth, uploadFrame, (req, res) => {
  if (!req.file || !req.file.buffer || req.file.buffer.length === 0) {
    return res.status(400).json({ error: 'frame file required' });
  }
  const slaveId = req.params.slaveId;
  const at = Date.now();
  frames.set(slaveId, { jpeg: req.file.buffer, at });
  res.json({ ok: true, at });
});

// Master: 최신 라이브 프레임 조회 — 3초 폴링으로 화면 갱신
r.get('/:slaveId/frame', (req, res) => {
  const f = frames.get(req.params.slaveId);
  if (!f || Date.now() - f.at > FRAME_TTL_MS) {
    return res.status(404).json({ error: 'no live frame' });
  }
  res.set({
    'Content-Type': 'image/jpeg',
    'Content-Length': f.jpeg.length,
    'Cache-Control': 'no-store',
    'x-frame-at': String(f.at),
  });
  res.send(f.jpeg);
});

// Master: 라이브 세션 상태 — 프레임 유무/최신 프레임 시각
r.get('/:slaveId/status', (req, res) => {
  const f = frames.get(req.params.slaveId);
  const active = !!f && Date.now() - f.at <= FRAME_TTL_MS;
  res.json({ active, lastFrameAt: active ? f.at : null });
});

export default r;
