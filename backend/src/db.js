import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import dotenv from 'dotenv';
import { seedMarket } from './lib/marketSeed.js';
dotenv.config();

const DB_PATH = process.env.DB_PATH || './data/oojoo.db';

// node:sqlite 는 상위 디렉터리를 자동 생성하지 않으므로 먼저 보장한다.
// (없으면 기동 시 SQLITE_CANTOPEN 으로 크래시)
const dir = path.dirname(DB_PATH);
if (dir && dir !== '.' && !fs.existsSync(dir)) {
  fs.mkdirSync(dir, { recursive: true });
}

const db = new DatabaseSync(DB_PATH);
db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA foreign_keys = ON;');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  nickname TEXT,
  region TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS slaves (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  name TEXT,
  session_key TEXT,
  online INTEGER DEFAULT 0,
  last_seen TEXT,
  battery INTEGER,
  created_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS pairings (
  code TEXT PRIMARY KEY,
  user_id TEXT,
  slave_id TEXT,
  status TEXT DEFAULT 'pending',
  created_at TEXT DEFAULT (datetime('now')),
  expires_at TEXT
);
CREATE TABLE IF NOT EXISTS plants (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  slave_id TEXT,
  name TEXT,
  species TEXT,
  planted_at TEXT,
  stage TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  slave_id TEXT,
  plant_id TEXT,
  type TEXT,
  payload TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS waterings (
  id TEXT PRIMARY KEY,
  slave_id TEXT,
  plant_id TEXT,
  amount_ml INTEGER,
  source TEXT,
  weather_factor REAL,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  slave_id TEXT,
  plant_id TEXT,
  action TEXT,
  amount_ml INTEGER,
  weather_factor REAL,
  status TEXT DEFAULT 'queued',
  created_at TEXT DEFAULT (datetime('now')),
  executed_at TEXT
);
CREATE TABLE IF NOT EXISTS weather_cache (
  region TEXT PRIMARY KEY,
  temp REAL,
  humidity REAL,
  precipitation REAL,
  weather_code INTEGER,
  updated_at TEXT DEFAULT (datetime('now'))
);
-- 슬레이브별 자율 실행 정책 (마스터가 원격 설정, 슬레이브가 조회)
CREATE TABLE IF NOT EXISTS policies (
  slave_id TEXT PRIMARY KEY,
  water_auto INTEGER DEFAULT 1,
  fan_auto INTEGER DEFAULT 1,
  laser_approval INTEGER DEFAULT 1,
  capture_interval INTEGER DEFAULT 60,
  region TEXT,
  updated_at TEXT DEFAULT (datetime('now'))
);

-- 마켓플레이스 (PRD 4.10)
CREATE TABLE IF NOT EXISTS products (
  id TEXT PRIMARY KEY,
  category TEXT,
  name TEXT,
  description TEXT,
  price INTEGER,
  vendor TEXT,            -- 'self' 또는 외부 제휴몰명
  image TEXT,             -- 이모지/URL
  affiliate_url TEXT,     -- 있으면 외부 제휴(CPS/CPA)
  stock INTEGER DEFAULT 0,
  rating REAL DEFAULT 0,
  tags TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS bundles (
  id TEXT PRIMARY KEY,
  name TEXT,
  description TEXT,
  price INTEGER,
  image TEXT,
  product_ids TEXT        -- 콤마구분 product id
);
CREATE TABLE IF NOT EXISTS orders (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  total INTEGER,
  status TEXT DEFAULT 'paid',
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS order_items (
  id TEXT PRIMARY KEY,
  order_id TEXT,
  product_id TEXT,
  name TEXT,
  qty INTEGER,
  price INTEGER
);
CREATE TABLE IF NOT EXISTS affiliate_clicks (
  id TEXT PRIMARY KEY,
  product_id TEXT,
  user_id TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);

-- 지역 기반 커뮤니티 (PRD 4.9 / 5.6): 나눔(share)/판매(sell)/구입(buy)
CREATE TABLE IF NOT EXISTS community_posts (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  type TEXT,               -- share | sell | buy
  title TEXT,
  crop TEXT,
  quantity TEXT,
  price INTEGER,           -- sell 일 때 가격(원), 그 외 null/0
  region TEXT,             -- 지역 단위 축약 (정밀좌표 비공개)
  description TEXT,
  image TEXT,              -- 이모지/URL
  status TEXT DEFAULT 'open',   -- open | reserved | done
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS community_comments (
  id TEXT PRIMARY KEY,
  post_id TEXT,
  user_id TEXT,
  body TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS community_reputation (
  user_id TEXT PRIMARY KEY,
  score REAL DEFAULT 0,
  deals INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS community_reports (
  id TEXT PRIMARY KEY,
  reporter_id TEXT,
  post_id TEXT,
  target_user_id TEXT,
  reason TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS community_blocks (
  blocker_id TEXT,
  blocked_id TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  PRIMARY KEY(blocker_id, blocked_id)
);

-- 조회 성능용 인덱스
CREATE INDEX IF NOT EXISTS idx_slaves_user      ON slaves(user_id);
CREATE INDEX IF NOT EXISTS idx_plants_user      ON plants(user_id);
CREATE INDEX IF NOT EXISTS idx_plants_slave     ON plants(slave_id);
CREATE INDEX IF NOT EXISTS idx_events_slave     ON events(slave_id, created_at);
CREATE INDEX IF NOT EXISTS idx_waterings_plant  ON waterings(plant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_commands_slave   ON commands(slave_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_products_cat     ON products(category);
CREATE INDEX IF NOT EXISTS idx_orders_user      ON orders(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_order_items_ord  ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_cposts_region    ON community_posts(region, type, created_at);
CREATE INDEX IF NOT EXISTS idx_cposts_user      ON community_posts(user_id);
CREATE INDEX IF NOT EXISTS idx_ccomments_post   ON community_comments(post_id, created_at);
`);

// 기존 DB 에 신규 컬럼이 없을 수 있으므로 안전하게 마이그레이션.
function ensureColumn(table, column, ddl) {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all();
  if (!cols.some((c) => c.name === column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${ddl};`);
  }
}
ensureColumn('slaves', 'battery', 'battery INTEGER');
ensureColumn('users', 'plan', "plan TEXT DEFAULT 'free'");

// 마켓 시드 (products 비어 있을 때만)
seedMarket(db);

export default db;
