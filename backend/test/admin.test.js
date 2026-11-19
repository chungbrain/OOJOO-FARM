// admin /stats 엔드포인트 통합 테스트 (node:test)
// apiUsage 그룹링 회귀: 2단 경로는 경로 그대로, 3단 이상은 /api/<도메인>
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-admin-test-${process.pid}.db`);
process.env.DB_PATH = tmpDb;

let server;
let base;

before(async () => {
  const { default: app } = await import('../src/app.js');
  await new Promise((resolve) => { server = app.listen(0, resolve); });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  try { server?.close(); } catch {}
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});

test('admin stats: apiUsage 그룹링 (2단=경로, 3단+=도메인, unknown 없음)', async () => {
  const db = (await import('../src/db.js')).default;
  const ins = db.prepare('INSERT INTO api_logs(method, path, status, duration, ip) VALUES(?,?,?,?,?)');
  ins.run('GET', '/api/users', 200, 1, 'test');          // 2단
  ins.run('GET', '/api/health', 200, 1, 'test');         // 2단 (다른 경로)
  ins.run('POST', '/api/pairing/code', 200, 1, 'test');  // 3단
  ins.run('POST', '/api/pairing/verify', 200, 1, 'test'); // 3단 (같은 도메인)
  ins.run('GET', '/api/weather/seoul', 200, 1, 'test');  // 3단

  const r = await fetch(base + '/api/admin/stats');
  assert.equal(r.status, 200);
  const j = await r.json();

  const ep = Object.fromEntries(j.apiUsage.map(x => [x.endpoint, x.count]));
  assert.equal(ep['/api/users'], 1);        // 과거: /api 로 잘림 → /api/unknown
  assert.equal(ep['/api/health'], 1);
  assert.equal(ep['/api/pairing'], 2);      // 3단은 도메인끼리 합쳐짐
  assert.equal(ep['/api/weather'], 1);
  assert.equal(j.apiUsage.find(x => x.endpoint === '/api/unknown'), undefined);

  assert.equal(typeof j.devices.users, 'number');
  assert.ok(Array.isArray(j.timeSeries));
  assert.ok(Array.isArray(j.crashes));
});
