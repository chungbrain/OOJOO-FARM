import { DatabaseSync } from 'node:sqlite';
import dotenv from 'dotenv';
dotenv.config();

const db = new DatabaseSync(process.env.DB_PATH || './data/oojoo.db');
db.exec('PRAGMA journal_mode = WAL;');

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
`);

export default db;
