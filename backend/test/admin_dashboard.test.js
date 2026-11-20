// admin dashboard 통합 테스트: /api/admin/dashboard 각 섹션 집계 + /admin 페이지 서빙
// 임시 DB 로 격리 (앱 import 전에 반드시 설정)
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-admin-dash-test-${process.pid}.db`);
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

test('dashboard: 개요/마스터/팜어/관계/마켓/커뮤니티/시스템 집계', async () => {
  const db = (await import('../src/db.js')).default;

  // 마스터 2명
  db.prepare("INSERT INTO users(id, nickname, region, plan) VALUES('u1', '마스터A', '경기', 'free')").run();
  db.prepare("INSERT INTO users(id, nickname, region) VALUES('u2', '마스터B', '서울')").run();
  // 팜어 2대 (1대 온라인)
  db.prepare("INSERT INTO slaves(id, user_id, name, session_key, online, last_seen, battery) VALUES('s1', 'u1', '팜어1', 'k1', 1, datetime('now'), 80)").run();
  db.prepare("INSERT INTO slaves(id, user_id, name, online, last_seen) VALUES('s2', 'u1', '팜어2', 0, datetime('now', '-2 day'))").run();
  // 관계: 페어링 + 가정(2 구성원)
  db.prepare("INSERT INTO pairings(code, user_id, slave_id, status) VALUES('C1', 'u1', 's1', 'used')").run();
  db.prepare("INSERT INTO households(id, owner_id, name) VALUES('h1', 'u1', '우리집')").run();
  db.prepare("INSERT INTO household_members(household_id, user_id, role) VALUES('h1', 'u1', 'owner')").run();
  db.prepare("INSERT INTO household_members(household_id, user_id, role) VALUES('h1', 'u2', 'member')").run();
  // 식물 + 팜어 활동
  db.prepare("INSERT INTO plants(id, user_id, slave_id, name, species) VALUES('p1', 'u1', 's1', '바질', 'basil')").run();
  db.prepare("INSERT INTO events(id, slave_id, plant_id, type, payload) VALUES('e1', 's1', 'p1', 'water_low', '{}')").run();
  db.prepare("INSERT INTO waterings(id, slave_id, plant_id, amount_ml, source) VALUES('w1', 's1', 'p1', 300, 'auto')").run();
  db.prepare("INSERT INTO commands(id, slave_id, action, status) VALUES('c1', 's1', 'water', 'queued')").run();
  // 마켓
  db.prepare("INSERT INTO orders(id, user_id, total, status) VALUES('o1', 'u1', 15000, 'paid')").run();
  db.prepare("INSERT INTO order_items(id, order_id, product_id, name, qty, price) VALUES('oi1', 'o1', 'x', '방울토마토 키트', 2, 7500)").run();
  // 커뮤니티
  db.prepare("INSERT INTO community_posts(id, user_id, type, title, crop, region) VALUES('cp1', 'u1', 'sell', '방울토마토 나눔', 'strawberry', '경기')").run();
  db.prepare("INSERT INTO community_comments(id, post_id, user_id, body) VALUES('cc1', 'cp1', 'u2', '좋아요')").run();
  db.prepare("INSERT INTO community_reputation(user_id, score, deals) VALUES('u1', 12.5, 3)").run();
  // 시스템: API 로그(정상 1 + 500 1) + 크래시
  db.prepare("INSERT INTO api_logs(method, path, status, duration, ip) VALUES('GET', '/api/users', 200, 5, '1.1.1.1')").run();
  db.prepare("INSERT INTO api_logs(method, path, status, duration, ip) VALUES('POST', '/api/boom', 500, 9, '1.1.1.1')").run();
  db.prepare("INSERT INTO crash_logs(id, source, device_id, error_message) VALUES('cr1', 'slave_app', 's1', 'valve timeout')").run();

  const r = await fetch(base + '/api/admin/dashboard');
  assert.equal(r.status, 200);
  const d = await r.json();

  // 개요
  assert.equal(d.overview.users, 2);
  assert.equal(d.overview.slaves.total, 2);
  assert.equal(d.overview.slaves.online, 1);
  assert.equal(d.overview.plants, 1);
  assert.equal(d.overview.orders, 1);
  assert.equal(d.overview.communityPosts, 1);
  assert.equal(d.overview.apiCalls24h, 2);
  assert.equal(d.overview.errors24h, 1);

  // 마스터 앱 활동
  const u1 = d.master.users.find((u) => u.id === 'u1');
  assert.equal(u1.nickname, '마스터A');
  assert.equal(u1.slaves, 2);
  assert.equal(u1.plants, 1);
  assert.equal(u1.orders, 1);
  assert.equal(u1.posts, 1);
  const ep = Object.fromEntries(d.master.apiUsage.map((x) => [x.endpoint, x.count]));
  assert.equal(ep['/api/users'], 1);
  assert.equal(d.master.recentApi.length, 2);

  // 팜어 활동 (온라인 기기 우선 정렬)
  assert.equal(d.farmer.slaves[0].id, 's1');
  const s1 = d.farmer.slaves.find((s) => s.id === 's1');
  assert.equal(s1.online, 1);
  assert.equal(s1.battery, 80);
  assert.equal(s1.events_24h, 1);
  assert.equal(s1.watered_ml_total, 300);
  assert.equal(d.farmer.recentEvents[0].type, 'water_low');
  assert.equal(d.farmer.recentEvents[0].slave_name, '팜어1');
  assert.equal(d.farmer.recentWaterings[0].amount_ml, 300);
  assert.equal(d.farmer.recentWaterings[0].plant_name, '바질');
  assert.equal(d.farmer.pendingCommands, 1);

  // 마스터 ↔ 팜어 관계
  assert.equal(d.relations.pairings.length, 1);
  assert.equal(d.relations.pairings[0].user_name, '마스터A');
  assert.equal(d.relations.pairings[0].slave_name, '팜어1');
  const h1 = d.relations.households[0];
  assert.equal(h1.members, 2);
  assert.equal(h1.members_list.length, 2);
  assert.equal(h1.members_list[0].role, 'owner');

  // 마켓
  assert.ok(d.market.products.total >= 1, 'seed market products exist');
  const paid = d.market.orders.byStatus.find((x) => x.status === 'paid');
  assert.equal(paid.count, 1);
  assert.equal(paid.total, 15000);
  assert.equal(d.market.orders.revenuePaid, 15000);
  assert.equal(d.market.topProducts[0].name, '방울토마토 키트');
  assert.equal(d.market.topProducts[0].qty, 2);
  assert.equal(d.market.topProducts[0].revenue, 15000);
  assert.equal(d.market.recentOrders[0].user_name, '마스터A');

  // 커뮤니티
  const st = Object.fromEntries(d.community.posts.byTypeStatus.map((x) => [`${x.type}|${x.status}`, x.count]));
  assert.equal(st['sell|open'], 1);
  assert.equal(d.community.posts.open, 1);
  assert.equal(d.community.comments, 1);
  assert.equal(d.community.reputationTop[0].score, 12.5);
  assert.equal(d.community.reputationTop[0].deals, 3);

  // 시스템
  assert.equal(d.system.recentErrors[0].path, '/api/boom');
  assert.equal(d.system.crashes[0].error_message, 'valve timeout');
  assert.ok(Array.isArray(d.system.hourly24h));
  assert.ok(Number.isInteger(d.system.uptimeSec));
  assert.ok(d.generatedAt);
});

test('/admin: self-contained 대시보드 페이지 서빙', async () => {
  const r = await fetch(base + '/admin');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-type') || '', /html/);
  const html = await r.text();
  assert.ok(html.includes('OOJOO-FARM 관리 대시보드'));
  assert.ok(html.includes('/api/admin/dashboard'));
  assert.ok(!/https?:\/\/[^"'\s]+/m.test(html.replace(/<!doctype html>/i, '')), 'no external CDN references');
});
