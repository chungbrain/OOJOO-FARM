import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { CATEGORIES } from '../lib/marketSeed.js';
const r = Router();

// 카테고리 목록 (상품 수 포함)
r.get('/categories', (req, res) => {
  const counts = db.prepare('SELECT category, COUNT(*) AS n FROM products GROUP BY category').all();
  const map = Object.fromEntries(counts.map((c) => [c.category, c.n]));
  res.json({ categories: CATEGORIES.map((c) => ({ ...c, count: map[c.key] || 0 })) });
});

// 상품 목록 (category, q 검색, 정렬)
r.get('/products', (req, res) => {
  const { category, q, sort } = req.query;
  const where = [];
  const args = [];
  if (category && category !== 'all') { where.push('category = ?'); args.push(category); }
  if (q) { where.push('(name LIKE ? OR description LIKE ? OR tags LIKE ?)'); const like = `%${q}%`; args.push(like, like, like); }
  let sql = 'SELECT * FROM products';
  if (where.length) sql += ' WHERE ' + where.join(' AND ');
  sql += sort === 'price' ? ' ORDER BY price ASC'
    : sort === 'rating' ? ' ORDER BY rating DESC'
    : ' ORDER BY rating DESC';
  res.json({ products: db.prepare(sql).all(...args) });
});

// 상품 상세
r.get('/products/:id', (req, res) => {
  const p = db.prepare('SELECT * FROM products WHERE id=?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'not found' });
  res.json(p);
});

// 번들 (구성 상품 포함)
r.get('/bundles', (req, res) => {
  const bundles = db.prepare('SELECT * FROM bundles').all().map((b) => {
    const ids = (b.product_ids || '').split(',').filter(Boolean);
    const items = ids.length
      ? db.prepare(`SELECT id, name, price, image FROM products WHERE id IN (${ids.map(() => '?').join(',')})`).all(...ids)
      : [];
    return { ...b, items };
  });
  res.json({ bundles });
});

// 맞춤 추천: 사용자의 식물 종/단계 + 필수 하드웨어 기반 (PRD 4.10 재배 중 맞춤 추천)
r.get('/recommendations/:userId', (req, res) => {
  const plants = db.prepare('SELECT species, stage FROM plants WHERE user_id=?').all(req.params.userId);
  const tags = new Set(['recommended']);
  for (const p of plants) {
    if (p.stage) tags.add(p.stage);
    if (p.species) {
      const s = p.species.toLowerCase();
      if (s.includes('토마토') || s.includes('tomato')) tags.add('tomato');
      if (s.includes('바질') || s.includes('basil') || s.includes('허브')) tags.add('herb');
      if (s.includes('상추') || s.includes('lettuce')) tags.add('lettuce');
    }
  }
  // 태그 매칭 점수 순 상위 6개 (식물 없으면 스타터 하드웨어 위주)
  const all = db.prepare('SELECT * FROM products').all();
  const scored = all.map((p) => {
    const ptags = (p.tags || '').split(',');
    let score = ptags.filter((t) => tags.has(t)).length;
    if (plants.length === 0 && ptags.includes('hardware')) score += 1;
    return { p, score };
  }).filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score || b.p.rating - a.p.rating)
    .slice(0, 6)
    .map((x) => x.p);
  res.json({ recommendations: scored });
});

// 제휴 링크 클릭 트래킹 → 이동 URL 반환 (CPS/CPA)
r.post('/affiliate/:id', (req, res) => {
  const p = db.prepare('SELECT id, affiliate_url FROM products WHERE id=?').get(req.params.id);
  if (!p) return res.status(404).json({ error: 'not found' });
  if (!p.affiliate_url) return res.status(400).json({ error: 'not an affiliate product' });
  db.prepare('INSERT INTO affiliate_clicks(id, product_id, user_id) VALUES(?,?,?)')
    .run(nanoid(12), p.id, req.body?.userId || null);
  res.json({ url: p.affiliate_url });
});

// 주문(체크아웃): 자체 상품 결제 시뮬레이션. items:[{productId, qty}]
r.post('/orders', (req, res) => {
  const { userId, items } = req.body;
  if (!userId || !Array.isArray(items) || items.length === 0) {
    return res.status(400).json({ error: 'userId and items required' });
  }
  const orderId = nanoid(12);
  let total = 0;
  const lines = [];
  for (const it of items) {
    const p = db.prepare('SELECT * FROM products WHERE id=?').get(it.productId);
    if (!p) return res.status(404).json({ error: `product ${it.productId} not found` });
    const qty = Math.max(1, Number(it.qty) || 1);
    total += p.price * qty;
    lines.push({ product: p, qty });
  }
  db.prepare('INSERT INTO orders(id, user_id, total, status) VALUES(?,?,?,?)').run(orderId, userId, total, 'paid');
  const insItem = db.prepare('INSERT INTO order_items(id, order_id, product_id, name, qty, price) VALUES(?,?,?,?,?,?)');
  for (const l of lines) {
    insItem.run(nanoid(12), orderId, l.product.id, l.product.name, l.qty, l.product.price);
    // 자체 상품 재고 차감
    if (l.product.vendor === 'self') {
      db.prepare('UPDATE products SET stock = MAX(0, stock - ?) WHERE id=?').run(l.qty, l.product.id);
    }
  }
  res.json({ orderId, total, status: 'paid', itemCount: lines.length });
});

// 주문 내역
r.get('/orders/:userId', (req, res) => {
  const orders = db.prepare('SELECT * FROM orders WHERE user_id=? ORDER BY created_at DESC').all(req.params.userId);
  const withItems = orders.map((o) => ({
    ...o,
    items: db.prepare('SELECT product_id, name, qty, price FROM order_items WHERE order_id=?').all(o.id),
  }));
  res.json({ orders: withItems });
});

export default r;
