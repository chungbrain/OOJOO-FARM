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
import './db.js';

const app = express();
app.use(cors());
app.use(express.json());

// 간단 요청 로깅 (외부 의존성 없이). 테스트 중에는 생략.
if (process.env.NODE_ENV !== 'test') {
  app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
      console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${Date.now() - start}ms`);
    });
    next();
  });
}

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

// 알 수 없는 경로
app.use((req, res) => {
  res.status(404).json({ error: 'not found', path: req.originalUrl });
});

// 에러 핸들러
app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'internal' });
});

export default app;
