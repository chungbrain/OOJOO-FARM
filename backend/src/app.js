import express from 'express';
import cors from 'cors';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
import photos from './routes/photos.js';
import analysis from './routes/analysis.js';
import diagnosis from './routes/diagnosis.js';
import admin from './routes/admin.js';
import crash from './routes/crash.js';
import household from './routes/household.js';
import harvests from './routes/harvests.js';
import messages from './routes/messages.js';
import live from './routes/live.js';
import payments, { tossWebhookHandler } from './routes/payments.js';
import './db.js';

const app = express();
app.use(cors());
// Toss PG 웹훅: HMAC 서명이 로우 바이트 기준이라 express.json()보다 먼저 raw로 읽는다.
app.post('/api/payments/toss/webhook', express.raw({ type: '*/*' }), tossWebhookHandler);
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
      // api_logs 테이블에 기록
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

app.get('/health', (req, res) => res.json({ ok: true, service: 'oojoo-farm', ts: Date.now() }));

// 모니터링 페이지 — self-contained HTML (CDN 없음), 데이터는 GET /api/admin/dashboard
const here = path.dirname(fileURLToPath(import.meta.url));
let adminPage;
app.get('/admin', (req, res) => {
  if (!adminPage) adminPage = readFileSync(path.join(here, 'admin.html'));
  res.type('html').send(adminPage);
});

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
app.use('/api/photos', photos);
app.use('/api/analysis', analysis);
app.use('/api/diagnosis', diagnosis);
app.use('/api/admin', admin);
app.use('/api/crash', crash);
app.use('/api/household', household);
app.use('/api/harvests', harvests);
app.use('/api/messages', messages);
app.use('/api/live', live);
app.use('/api/payments', payments);

// 알 수 없는 경로
app.use((req, res) => {
  res.status(404).json({ error: 'not found', path: req.originalUrl });
});

// 에러 핸들러 — 클라이언트 에러(4xx)는 status 그대로, 서버 에러(5xx)만 크래시 기록
app.use((err, req, res, next) => {
  const status = Number.isInteger(err.status) ? err.status : 500;
  if (status >= 500) {
    console.error(err);

    // 백엔드 크래시를 DB에 기록
    try {
      const id = nanoid(12);
      db.prepare('INSERT INTO crash_logs(id, source, device_id, error_message, stack_trace) VALUES(?,?,?,?,?)')
        .run(id, 'backend', 'server', err.message || String(err), err.stack || '');
    } catch (dbErr) {
      console.error('Failed to log crash to DB:', dbErr);
    }
  }
  if (res.headersSent) return next(err);
  res.status(status).json({ error: status >= 500 ? 'internal' : (err.message || 'bad request') });
});

export default app;
