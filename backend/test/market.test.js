// 마켓플레이스 통합 테스트
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.NODE_ENV = 'test';
const tmpDb = path.join(os.tmpdir(), `oojoo-market-${process.pid}.db`);
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
  for (const f of [tmpDb, `${tmpDb}-wal`, `${tmpDb}-shm`]) { try { fs.rmSync(f); } catch {} }
});

const get = (p) => fetch(base + p);
const post = (p, b) => fetch(base + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });

test('카테고리 + 시드 상품이 존재', async () => {
  const cats = await (await get('/api/market/categories')).json();
  assert.ok(cats.categories.length >= 5);
  assert.ok(cats.categories.some((c) => c.count > 0));

  const prods = await (await get('/api/market/products')).json();
  assert.ok(prods.products.length >= 10);
});

test('카테고리 필터 + 검색', async () => {
  const ctrl = await (await get('/api/market/products?category=controller')).json();
  assert.ok(ctrl.products.length >= 1);
  assert.ok(ctrl.products.every((p) => p.category === 'controller'));

  const search = await (await get('/api/market/products?q=토마토')).json();
  assert.ok(search.products.some((p) => p.name.includes('토마토')));
});

test('번들은 구성 상품을 포함', async () => {
  const b = await (await get('/api/market/bundles')).json();
  assert.ok(b.bundles.length >= 1);
  assert.ok(b.bundles[0].items.length >= 1);
});

test('제휴 상품 클릭 → URL 반환 / 자체상품은 400', async () => {
  const aff = await post('/api/market/affiliate/prd_phone_01', { userId: 'u1' });
  assert.equal(aff.status, 200);
  assert.ok((await aff.json()).url.startsWith('http'));

  const self = await post('/api/market/affiliate/prd_ctrl_01', { userId: 'u1' });
  assert.equal(self.status, 400);
});

test('주문 생성 → 합계/재고 반영 + 내역 조회', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();

  const before = await (await get('/api/market/products/prd_seed_01')).json();

  const order = await post('/api/market/orders', {
    userId: u.id,
    items: [{ productId: 'prd_seed_01', qty: 2 }, { productId: 'prd_ctrl_01', qty: 1 }],
  });
  assert.equal(order.status, 200);
  const o = await order.json();
  assert.equal(o.total, before.price * 2 + 9900);
  assert.equal(o.itemCount, 2);

  // 자체상품 재고 차감
  const after = await (await get('/api/market/products/prd_seed_01')).json();
  assert.equal(after.stock, before.stock - 2);

  // 내역
  const hist = await (await get(`/api/market/orders/${u.id}`)).json();
  assert.equal(hist.orders.length, 1);
  assert.equal(hist.orders[0].items.length, 2);
});

test('맞춤 추천: 식물 등록 후 관련 상품 반환', async () => {
  const u = await (await post('/api/users', { region: 'Seoul' })).json();
  await post('/api/plants', { userId: u.id, name: '방울토마토', species: '토마토', stage: 'fruiting' });
  const rec = await (await get(`/api/market/recommendations/${u.id}`)).json();
  assert.ok(rec.recommendations.length >= 1);
});
