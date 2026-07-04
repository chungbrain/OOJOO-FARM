import db from '../db.js';

// 슬레이브 세션키 인증.
// 슬레이브는 페어링 verify 로 발급받은 sessionKey 를 'x-session-key' 헤더로 전송한다.
// slaveId 는 body 또는 URL 파라미터에서 찾는다.
// 인증에 성공하면 req.slave 에 슬레이브 레코드를 주입한다.
export function slaveAuth(req, res, next) {
  const sessionKey = req.get('x-session-key');
  const slaveId = req.body?.slaveId || req.params?.slaveId;

  if (!slaveId) return res.status(400).json({ error: 'slaveId required' });
  if (!sessionKey) return res.status(401).json({ error: 'session key required' });

  const slave = db.prepare('SELECT * FROM slaves WHERE id=?').get(slaveId);
  if (!slave) return res.status(404).json({ error: 'slave not found' });
  if (slave.session_key !== sessionKey) {
    return res.status(403).json({ error: 'invalid session key' });
  }

  req.slave = slave;
  next();
}

// 관대(선택) 인증: 헤더가 있으면 검증하고, 없으면 통과.
// Phase1 하위호환용 — 헤더를 아직 보내지 않는 클라이언트를 막지 않는다.
export function slaveAuthOptional(req, res, next) {
  const sessionKey = req.get('x-session-key');
  if (!sessionKey) return next();
  const slaveId = req.body?.slaveId || req.params?.slaveId;
  if (!slaveId) return next();
  const slave = db.prepare('SELECT * FROM slaves WHERE id=?').get(slaveId);
  if (slave && slave.session_key === sessionKey) req.slave = slave;
  else if (slave) return res.status(403).json({ error: 'invalid session key' });
  next();
}
