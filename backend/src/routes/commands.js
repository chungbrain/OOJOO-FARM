import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';

const r = Router();

// SSE 클라이언트 레지스트리 — slaveId별 연결된 Slave와 Master 클라이언트.
// Slave는 명령을 수신하고, Master는 영상/이벤트 알림을 수신한다.
const slaveSSEClients = new Map(); // slaveId -> Set<res>
const masterSSEClients = new Map(); // slaveId -> Set<res>

/** Slave SSE 클라이언트에 이벤트 전송 (새 명령 알림). */
export function notifySlaveCommand(slaveId, command) {
  const clients = slaveSSEClients.get(slaveId);
  if (!clients) return;
  const data = `data: ${JSON.stringify({ type: 'command', command })}\n\n`;
  for (const res of clients) {
    try { res.write(data); } catch (_) {}
  }
}

/** Master SSE 클라이언트에 이벤트 전송 (영상 준비 완료 등). */
export function notifyMasterEvent(slaveId, event) {
  const clients = masterSSEClients.get(slaveId);
  if (!clients) return;
  const data = `data: ${JSON.stringify(event)}\n\n`;
  for (const res of clients) {
    try { res.write(data); } catch (_) {}
  }
}

// 마스터: 슬레이브에 원격 지시 등록 (관수/퇴치/모드변경 등)
r.post('/', (req, res) => {
  const { slaveId, plantId, action = 'water', amountMl = 300, weatherFactor = 1 } = req.body;
  if (!slaveId) return res.status(400).json({ error: 'slaveId required' });
  const id = nanoid(12);
  db.prepare('INSERT INTO commands(id, slave_id, plant_id, action, amount_ml, weather_factor) VALUES(?,?,?,?,?,?)')
    .run(id, slaveId, plantId || null, action, amountMl, weatherFactor);
  // SSE: 연결된 Slave에게 즉시 명령 전송
  const command = { id, slave_id: slaveId, plant_id: plantId || null, action, amount_ml: amountMl, weather_factor: weatherFactor, status: 'queued' };
  notifySlaveCommand(slaveId, command);
  res.json({ commandId: id, status: 'queued' });
});

// 슬레이브: 대기 중 명령 조회 (폴링 fallback — SSE 미연결 시 사용)
r.get('/pending/:slaveId', slaveAuth, (req, res) => {
  const rows = db.prepare("SELECT * FROM commands WHERE slave_id=? AND status='queued' ORDER BY created_at ASC").all(req.params.slaveId);
  res.json({ commands: rows });
});

// 슬레이브: 명령 실행 완료 보고
r.post('/:id/done', (req, res) => {
  const { id } = req.params;
  const cmd = db.prepare('SELECT * FROM commands WHERE id=?').get(id);
  if (!cmd) return res.status(404).json({ error: 'not found' });
  db.prepare("UPDATE commands SET status='done', executed_at=datetime('now') WHERE id=?").run(id);
  res.json({ ok: true });
});

// 마스터: 명령 이력 조회
r.get('/history/:slaveId', (req, res) => {
  const rows = db.prepare('SELECT * FROM commands WHERE slave_id=? ORDER BY created_at DESC LIMIT 50').all(req.params.slaveId);
  res.json({ commands: rows });
});

// === SSE 엔드포인트 ===

// 슬레이브: 명령 스트림 (세션키 인증)
r.get('/sse/slave/:slaveId', slaveAuth, (req, res) => {
  const slaveId = req.params.slaveId;
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.write(`data: ${JSON.stringify({ type: 'connected', slaveId })}\n\n`);

  if (!slaveSSEClients.has(slaveId)) slaveSSEClients.set(slaveId, new Set());
  slaveSSEClients.get(slaveId).add(res);

  // 연결 종료 시 정리
  const cleanup = () => {
    const clients = slaveSSEClients.get(slaveId);
    if (clients) {
      clients.delete(res);
      if (clients.size === 0) slaveSSEClients.delete(slaveId);
    }
  };
  req.on('close', cleanup);
  req.on('error', cleanup);

  // keep-alive heartbeat (30초마다)
  const heartbeat = setInterval(() => {
    try { res.write(': heartbeat\n\n'); } catch (_) { cleanup(); clearInterval(heartbeat); }
  }, 30000);
  req.on('close', () => clearInterval(heartbeat));
});

// 마스터: 이벤트 스트림 (영상 준비 완료 등)
r.get('/sse/master/:slaveId', (req, res) => {
  const slaveId = req.params.slaveId;
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.write(`data: ${JSON.stringify({ type: 'connected', slaveId })}\n\n`);

  if (!masterSSEClients.has(slaveId)) masterSSEClients.set(slaveId, new Set());
  masterSSEClients.get(slaveId).add(res);

  const cleanup = () => {
    const clients = masterSSEClients.get(slaveId);
    if (clients) {
      clients.delete(res);
      if (clients.size === 0) masterSSEClients.delete(slaveId);
    }
  };
  req.on('close', cleanup);
  req.on('error', cleanup);

  const heartbeat = setInterval(() => {
    try { res.write(': heartbeat\n\n'); } catch (_) { cleanup(); clearInterval(heartbeat); }
  }, 30000);
  req.on('close', () => clearInterval(heartbeat));
});

export default r;
