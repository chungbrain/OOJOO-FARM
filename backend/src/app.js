import express from 'express';
import cors from 'cors';
import users from './routes/users.js';
import pairing from './routes/pairing.js';
import plants from './routes/plants.js';
import events from './routes/events.js';
import watering from './routes/watering.js';
import commands from './routes/commands.js';
import policy from './routes/policy.js';
import notifications from './routes/notifications.js';
import market from './routes/market.js';
import community from './routes/community.js';
import report from './routes/report.js';
import subscription from './routes/subscription.js';
import weather from './routes/weather.js';
import videos from './routes/videos.js';
import analysis from './routes/analysis.js';
import admin from './routes/admin.js';
import crash from './routes/crash.js';
import './db.js';

const app = express();
app.use(cors());
app.use(express.json());

import db from './db.js';
import { nanoid } from 'nanoid';

// 간단 요청 로깅 (외부 의존성 없이). 테스트 중에는 생략.
if (process.env.NODE_ENV !== 'test') {
  app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
      const duration = Date.now() - start;
      const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '';
      console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${duration}ms`);
      // api_logs 테이블에 기록 (단, 정적 파일이나 대시보드 자체 조회는 제외 가능)
      if (req.originalUrl.startsWith('/api/') && !req.originalUrl.startsWith('/api/admin/')) {
        try {
          db.prepare('INSERT INTO api_logs(method, path, status, duration, ip) VALUES(?,?,?,?,?)')
            .run(req.method, req.originalUrl.split('?')[0], res.statusCode, duration, ip);
        } catch (e) {
          console.error('api_logs insert error:', e);
        }
      }
    });
    next();
  });
}

// 대시보드용 정적 파일 서빙
import path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
app.use('/dashboard', express.static(path.join(__dirname, '../public/dashboard')));

app.get('/health', (req, res) => res.json({ ok: true, service: 'oojoo-farm', ts: Date.now() }));

app.use('/api/users', users);
app.use('/api/pairing', pairing);
app.use('/api/plants', plants);
app.use('/api/events', events);
app.use('/api/watering', watering);
app.use('/api/commands', commands);
app.use('/api/policy', policy);
app.use('/api/notifications', notifications);
app.use('/api/market', market);
app.use('/api/community', community);
app.use('/api/report', report);
app.use('/api/subscription', subscription);
app.use('/api/weather', weather);
app.use('/api/videos', videos);
app.use('/api/analysis', analysis);
app.use('/api/admin', admin);
app.use('/api/crash', crash);

// 알 수 없는 경로
app.use((req, res) => {
  res.status(404).json({ error: 'not found', path: req.originalUrl });
});

// 에러 핸들러
app.use((err, req, res, next) => {
  console.error(err);
  
  // 백엔드 크래시를 DB에 기록
  try {
    const id = nanoid(12);
    db.prepare('INSERT INTO crash_logs(id, source, device_id, error_message, stack_trace) VALUES(?,?,?,?,?)')
      .run(id, 'backend', 'server', err.message || String(err), err.stack || '');
  } catch (dbErr) {
    console.error('Failed to log crash to DB:', dbErr);
  }

  res.status(500).json({ error: 'internal' });
});

export default app;
