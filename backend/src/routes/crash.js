import { Router } from 'express';
import db from '../db.js';
import { nanoid } from 'nanoid';

const r = Router();

r.post('/report', (req, res) => {
  const { source, deviceId, errorMessage, stackTrace } = req.body;
  if (!source || !errorMessage) {
    return res.status(400).json({ error: 'source, errorMessage required' });
  }

  try {
    const id = nanoid(12);
    db.prepare('INSERT INTO crash_logs(id, source, device_id, error_message, stack_trace) VALUES(?,?,?,?,?)')
      .run(id, source, deviceId || 'unknown', errorMessage, stackTrace || '');
    res.json({ ok: true, crashId: id });
  } catch (err) {
    console.error('Crash report save error:', err);
    res.status(500).json({ error: 'internal' });
  }
});

export default r;
