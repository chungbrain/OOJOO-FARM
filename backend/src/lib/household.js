import { nanoid } from 'nanoid';
import db from '../db.js';

export function householdForUser(userId) {
  const row = db.prepare(`
    SELECT h.* FROM household_members m
    JOIN households h ON h.id = m.household_id
    WHERE m.user_id=? AND m.status='active'
    LIMIT 1
  `).get(userId);
  return row || null;
}

export function getOrCreateHousehold(userId) {
  const existing = householdForUser(userId);
  if (existing) return existing;
  db.prepare('INSERT OR IGNORE INTO users(id) VALUES(?)').run(userId);
  const id = nanoid(12);
  db.prepare('INSERT INTO households(id, owner_id, name) VALUES(?,?,?)').run(id, userId, '우리 농장');
  db.prepare("INSERT INTO household_members(household_id, user_id, role, status) VALUES(?,?, 'owner', 'active')")
    .run(id, userId);
  return db.prepare('SELECT * FROM households WHERE id=?').get(id);
}

export function householdMemberIds(userId) {
  const hh = householdForUser(userId);
  if (!hh) return [userId];
  const rows = db.prepare("SELECT user_id FROM household_members WHERE household_id=? AND status='active'").all(hh.id);
  const ids = rows.map((r) => r.user_id);
  return ids.length ? ids : [userId];
}

export function inSql(ids) {
  return ids.map(() => '?').join(',');
}
