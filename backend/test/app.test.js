// app-level 에러 핸들러/루트 통합 테스트 (node:test)
// - 4xx(잘못된 JSON, 과대 페이로드)는 status 그대로 반환 (과거: 전부 500)
// - 4xx 는 crash_logs 를 오염시키지 않음
// - 죽은 /dashboard 정적 루트 제거 회귀 (JSON 404 fallthrough)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-app-test-${process.pid}.db`);
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

test('잘못된 JSON body 는 400 (과거: 500)', async () => {
  const r = await fetch(base + '/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{"nickname": broken',
  });
  assert.equal(r.status, 400);
  const j = await r.json();
  assert.ok(j.error);
});

test('과대 페이로드는 413 (과거: 500)', async () => {
  const big = JSON.stringify({ nickname: 'x'.repeat(200 * 1024) });
  const r = await fetch(base + '/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: big,
  });
  assert.equal(r.status, 413);
});

test('4xx 는 crash_logs 를 오염시키지 않는다', async () => {
  const r = await fetch(base + '/api/admin/stats');
  const j = await r.json();
  assert.deepEqual(j.crashes, []);
});

test('삭제된 /dashboard 정적 루트: JSON 404 fallthrough', async () => {
  const r = await fetch(base + '/dashboard');
  assert.equal(r.status, 404);
  const j = await r.json();
  assert.equal(j.error, 'not found');
});

test('SIGTERM graceful shutdown: exit code 0, DB close', async () => {
  const tmp = path.join(os.tmpdir(), `oojoo-shutdown-${process.pid}.db`);
  const here = path.dirname(fileURLToPath(import.meta.url));
  const port = 24000 + (process.pid % 20000);
  const child = spawn(process.execPath, ['--experimental-sqlite', path.join(here, '../src/server.js')], {
    env: { ...process.env, NODE_ENV: 'test', DB_PATH: tmp, PORT: String(port), HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  // listen 로그 대기 (최대 10s)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('server did not start in 10s')), 10000);
    child.stdout.on('data', (d) => {
      if (String(d).includes('OOJOO FARM backend on')) { clearTimeout(t); resolve(); }
    });
  });

  const code = await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('server did not exit within 8s of SIGTERM')), 8000);
    child.on('exit', (c) => { clearTimeout(t); resolve(c); });
    child.kill('SIGTERM');
  });

  assert.equal(code, 0);
  for (const f of [tmp, `${tmp}-wal`, `${tmp}-shm`]) {
    try { fs.rmSync(f); } catch {}
  }
});
