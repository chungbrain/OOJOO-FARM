import dotenv from 'dotenv';
import app from './app.js';
import db from './db.js';

dotenv.config();

const port = Number(process.env.PORT || 4000);
const host = process.env.HOST || '0.0.0.0';
const server = app.listen(port, host, () => {
  console.log(`OOJOO FARM backend on http://${host}:${port}`);
  console.log(`Emulator URL: http://10.0.2.2:${port}/`);
});

// Graceful shutdown: 새 연결 중단 → in-flight 완료 대기 → SSE 등 남은 소켓 끊음 → DB close (WAL checkpoint)
let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[OOJOO] ${signal} received, shutting down gracefully...`);
  server.close(() => {
    try { db.close(); } catch { /* 이미 닫힘 */ }
    console.log('[OOJOO] closed');
    process.exit(0);
  });
  // in-flight 요청은 3초까지 대기, 그 뒤에는 SSE 등 장기 소켓을 강제로 끊음
  setTimeout(() => {
    try { server.closeAllConnections?.(); } catch { /* noop */ }
  }, 3000).unref();
  // 그래도 못 닫히면 강제 종료
  setTimeout(() => {
    console.error('[OOJOO] forced exit after 10s grace period');
    process.exit(1);
  }, 10000).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
