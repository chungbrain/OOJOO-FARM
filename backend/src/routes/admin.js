import { Router } from 'express';
import db from '../db.js';

const r = Router();

// /stats 와 /dashboard 가 공유하는 집계 쿼리 (drift 방지용 헬퍼)
function apiUsageTop(limit = 10) {
  // 2단 경로(/api/users)는 instr=0 이라 ELSE path, 3단 이상은 첫 도메인까지 자름
  return db.prepare(`
    SELECT 
      CASE
        WHEN instr(substr(path, 6), '/') > 0
          THEN substr(path, 1, instr(substr(path, 6), '/') + 4)
        ELSE path
      END as endpoint,
      COUNT(*) as count 
    FROM api_logs 
    WHERE path LIKE '/api/%'
    GROUP BY endpoint 
    ORDER BY count DESC 
    LIMIT ?
  `).all(limit);
}

function apiHourly24h() {
  return db.prepare(`
    SELECT strftime('%Y-%m-%d %H:00', created_at) as hour, COUNT(*) as count 
    FROM api_logs 
    WHERE created_at > datetime('now', '-1 day')
    GROUP BY hour 
    ORDER BY hour ASC
  `).all();
}

function recentCrashes(limit = 20) {
  return db.prepare(`
    SELECT id, source, device_id, error_message, created_at 
    FROM crash_logs 
    ORDER BY created_at DESC 
    LIMIT ?
  `).all(limit);
}

function count(sql) {
  return db.prepare(sql).get().count;
}

r.get('/stats', (req, res) => {
  try {
    const usersCount = count('SELECT COUNT(*) as count FROM users');
    const activeSlaves = count("SELECT COUNT(*) as count FROM slaves WHERE online = 1 OR last_seen > datetime('now', '-1 day')");

    res.json({
      devices: {
        users: usersCount,
        activeSlaves: activeSlaves
      },
      apiUsage: apiUsageTop(10),
      crashes: recentCrashes(20),
      timeSeries: apiHourly24h()
    });
  } catch (error) {
    console.error('Admin stats error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

// GET /api/admin/dashboard — 모니터링 페이지용 전체 집계.
// 기존 admin API와 동일한 신뢰 모델 (PRD상 마스터 API 비인증).
r.get('/dashboard', (req, res) => {
  try {
    // 1) 개요
    const overview = {
      users: count('SELECT COUNT(*) as count FROM users'),
      slaves: {
        total: count('SELECT COUNT(*) as count FROM slaves'),
        online: count('SELECT COUNT(*) as count FROM slaves WHERE online = 1')
      },
      plants: count('SELECT COUNT(*) as count FROM plants'),
      orders: count('SELECT COUNT(*) as count FROM orders'),
      communityPosts: count('SELECT COUNT(*) as count FROM community_posts'),
      apiCalls24h: count("SELECT COUNT(*) as count FROM api_logs WHERE created_at > datetime('now', '-1 day')"),
      errors24h: count("SELECT COUNT(*) as count FROM api_logs WHERE created_at > datetime('now', '-1 day') AND status >= 500")
    };

    // 2) 마스터 앱 활동 — 사용자별 사용 요약 + API 도메인 사용량 + 최근 요청
    const masterUsers = db.prepare(`
      SELECT u.id, u.nickname, u.region, u.plan, u.created_at,
        (SELECT COUNT(*) FROM slaves s WHERE s.user_id = u.id) AS slaves,
        (SELECT COUNT(*) FROM plants p WHERE p.user_id = u.id) AS plants,
        (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS orders,
        (SELECT COUNT(*) FROM community_posts cp WHERE cp.user_id = u.id) AS posts
      FROM users u
      ORDER BY u.created_at DESC
      LIMIT 100
    `).all();
    const recentApi = db.prepare(`
      SELECT method, path, status, duration, ip, created_at
      FROM api_logs ORDER BY id DESC LIMIT 20
    `).all();
    const master = {
      users: masterUsers,
      apiUsage: apiUsageTop(10),
      recentApi
    };

    // 3) 팜어 활동 — 기기 상태 + 이벤트/급수 흐름
    const farmerSlaves = db.prepare(`
      SELECT s.id, s.name, s.user_id, u.nickname AS user_name, s.online, s.last_seen, s.battery, s.created_at,
        (SELECT COUNT(*) FROM events e WHERE e.slave_id = s.id AND e.created_at > datetime('now', '-1 day')) AS events_24h,
        (SELECT COALESCE(SUM(w.amount_ml), 0) FROM waterings w WHERE w.slave_id = s.id) AS watered_ml_total
      FROM slaves s
      LEFT JOIN users u ON u.id = s.user_id
      ORDER BY (s.online = 0), s.last_seen DESC
      LIMIT 100
    `).all();
    const recentEvents = db.prepare(`
      SELECT e.id, e.slave_id, s.name AS slave_name, e.type, e.payload, e.created_at
      FROM events e
      LEFT JOIN slaves s ON s.id = e.slave_id
      ORDER BY e.created_at DESC
      LIMIT 20
    `).all();
    const recentWaterings = db.prepare(`
      SELECT w.id, w.slave_id, s.name AS slave_name, w.plant_id, p.name AS plant_name,
        w.amount_ml, w.source, w.created_at
      FROM waterings w
      LEFT JOIN slaves s ON s.id = w.slave_id
      LEFT JOIN plants p ON p.id = w.plant_id
      ORDER BY w.created_at DESC
      LIMIT 20
    `).all();
    const farmer = {
      slaves: farmerSlaves,
      recentEvents,
      recentWaterings,
      pendingCommands: count("SELECT COUNT(*) as count FROM commands WHERE status = 'queued'")
    };

    // 4) 마스터 ↔ 팜어 관계 — 페어링 + 가정(가구)
    const pairings = db.prepare(`
      SELECT p.code, p.user_id, p.slave_id, p.status, p.created_at,
        u.nickname AS user_name, s.name AS slave_name
      FROM pairings p
      LEFT JOIN users u ON u.id = p.user_id
      LEFT JOIN slaves s ON s.id = p.slave_id
      ORDER BY p.created_at DESC
      LIMIT 100
    `).all();
    const households = db.prepare(`
      SELECT h.id, h.name, h.owner_id, o.nickname AS owner_name, h.created_at,
        (SELECT COUNT(*) FROM household_members m WHERE m.household_id = h.id AND m.status = 'active') AS members
      FROM households h
      LEFT JOIN users o ON o.id = h.owner_id
      ORDER BY h.created_at DESC
      LIMIT 50
    `).all().map((h) => ({
      ...h,
      members_list: db.prepare(`
        SELECT u.nickname, m.role
        FROM household_members m
        JOIN users u ON u.id = m.user_id
        WHERE m.household_id = ? AND m.status = 'active'
      `).all(h.id)
    }));
    const relations = { pairings, households };

    // 5) 마켓 활동
    const productRow = db.prepare('SELECT COUNT(*) as count, COALESCE(SUM(stock), 0) as stock FROM products').get();
    const orderStats = db.prepare(`
      SELECT status, COUNT(*) as count, COALESCE(SUM(total), 0) as total
      FROM orders GROUP BY status
    `).all();
    const topProducts = db.prepare(`
      SELECT oi.name, SUM(oi.qty) as qty, SUM(oi.qty * oi.price) as revenue
      FROM order_items oi
      JOIN orders o ON o.id = oi.order_id
      GROUP BY oi.name
      ORDER BY revenue DESC
      LIMIT 10
    `).all();
    const recentOrders = db.prepare(`
      SELECT o.id, o.user_id, o.total, o.status, o.created_at, u.nickname AS user_name
      FROM orders o
      LEFT JOIN users u ON u.id = o.user_id
      ORDER BY o.created_at DESC
      LIMIT 10
    `).all();
    const affiliateRow = db.prepare(`
      SELECT COUNT(*) as total,
        COALESCE(SUM(CASE WHEN created_at > datetime('now', '-7 day') THEN 1 ELSE 0 END), 0) as week
      FROM affiliate_clicks
    `).get();
    const market = {
      products: { total: productRow.count, stock: productRow.stock },
      orders: {
        total: count('SELECT COUNT(*) as count FROM orders'),
        byStatus: orderStats,
        revenuePaid: db.prepare("SELECT COALESCE(SUM(total), 0) as total FROM orders WHERE status = 'paid'").get().total,
        revenue24h: db.prepare("SELECT COALESCE(SUM(total), 0) as total FROM orders WHERE status = 'paid' AND created_at > datetime('now', '-1 day')").get().total
      },
      topProducts,
      recentOrders,
      affiliate: { total: affiliateRow.total, week: affiliateRow.week }
    };

    // 6) 커뮤니티 활동
    const community = {
      posts: {
        total: count('SELECT COUNT(*) as count FROM community_posts'),
        byTypeStatus: db.prepare('SELECT type, status, COUNT(*) as count FROM community_posts GROUP BY type, status').all(),
        open: count("SELECT COUNT(*) as count FROM community_posts WHERE status = 'open'")
      },
      comments: count('SELECT COUNT(*) as count FROM community_comments'),
      recentPosts: db.prepare(`
        SELECT cp.id, cp.type, cp.title, cp.crop, cp.region, cp.status, cp.created_at, u.nickname AS user_name
        FROM community_posts cp
        LEFT JOIN users u ON u.id = cp.user_id
        ORDER BY cp.created_at DESC
        LIMIT 10
      `).all(),
      reputationTop: db.prepare(`
        SELECT r.user_id, r.score, r.deals, u.nickname
        FROM community_reputation r
        LEFT JOIN users u ON u.id = r.user_id
        ORDER BY r.score DESC
        LIMIT 10
      `).all()
    };

    // 7) 시스템 — API 트래픽/오류 + 크래시
    const system = {
      uptimeSec: Math.round(process.uptime()),
      hourly24h: apiHourly24h(),
      topEndpoints: apiUsageTop(10),
      recentErrors: db.prepare(`
        SELECT id, method, path, status, duration, ip, created_at
        FROM api_logs WHERE status >= 500
        ORDER BY id DESC LIMIT 10
      `).all(),
      crashes: recentCrashes(20)
    };

    res.json({ generatedAt: new Date().toISOString(), overview, master, farmer, relations, market, community, system });
  } catch (error) {
    console.error('Admin dashboard error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

export default r;
