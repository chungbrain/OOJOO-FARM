import { Router } from 'express';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { getOrCreateHousehold, householdForUser } from '../lib/household.js';

const r = Router();

function publicMember(row) {
  return {
    userId: row.user_id,
    role: row.role,
    status: row.status,
    email: row.email || null,
    nickname: row.nickname || null,
    joinedAt: row.created_at,
  };
}

function householdPayload(userId) {
  const hh = getOrCreateHousehold(userId);
  const members = db.prepare(`
    SELECT m.user_id, m.role, m.status, m.created_at, u.email, u.nickname
    FROM household_members m
    LEFT JOIN users u ON u.id = m.user_id
    WHERE m.household_id=? AND m.status='active'
    ORDER BY CASE m.role WHEN 'owner' THEN 0 ELSE 1 END, m.created_at
  `).all(hh.id);
  const myRole = members.find((m) => m.user_id === userId)?.role || 'member';
  const invites = myRole === 'owner'
    ? db.prepare(`
        SELECT code, invited_email, status, expires_at, created_at
        FROM household_invites
        WHERE household_id=? AND status='pending' AND (expires_at IS NULL OR expires_at > datetime('now'))
        ORDER BY created_at DESC
      `).all(hh.id)
    : [];
  return {
    household: { id: hh.id, name: hh.name || '우리 농장', ownerId: hh.owner_id },
    role: myRole,
    members: members.map(publicMember),
    invites,
  };
}

r.get('/:userId', (req, res) => {
  const { userId } = req.params;
  if (!userId) return res.status(400).json({ error: 'userId required' });
  res.json(householdPayload(userId));
});

r.post('/invite', (req, res) => {
  const { userId, email } = req.body || {};
  if (!userId) return res.status(400).json({ error: 'userId required' });
  const hh = getOrCreateHousehold(userId);
  const me = db.prepare("SELECT role FROM household_members WHERE household_id=? AND user_id=? AND status='active'").get(hh.id, userId);
  if (!me || me.role !== 'owner') return res.status(403).json({ error: 'owner only' });

  const invitedEmail = (email || '').trim().toLowerCase() || null;
  if (invitedEmail) {
    const already = db.prepare(`
      SELECT m.user_id FROM household_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.household_id=? AND m.status='active' AND lower(u.email)=?
    `).get(hh.id, invitedEmail);
    if (already) return res.status(409).json({ error: 'already a member' });
  }

  db.prepare("DELETE FROM household_invites WHERE household_id=? AND status='pending' AND expires_at < datetime('now')").run(hh.id);
  const code = nanoid(6).toUpperCase();
  const expires = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
  db.prepare('INSERT INTO household_invites(code, household_id, invited_email, invited_by, status, expires_at) VALUES(?,?,?,?,?,?)')
    .run(code, hh.id, invitedEmail, userId, 'pending', expires);
  res.json({ code, expiresAt: expires, householdId: hh.id, invitedEmail });
});

r.post('/accept', (req, res) => {
  const { userId, code } = req.body || {};
  if (!userId || !code) return res.status(400).json({ error: 'userId, code required' });
  const invite = db.prepare('SELECT * FROM household_invites WHERE code=?').get(String(code).toUpperCase());
  if (!invite) return res.status(404).json({ error: 'invalid code' });
  if (invite.status !== 'pending') return res.status(410).json({ error: 'code already used' });
  if (invite.expires_at && new Date(invite.expires_at) < new Date()) return res.status(410).json({ error: 'code expired' });

  const already = db.prepare("SELECT * FROM household_members WHERE household_id=? AND user_id=? AND status='active'").get(invite.household_id, userId);
  if (already) {
    db.prepare("UPDATE household_invites SET status='accepted' WHERE code=?").run(invite.code);
    return res.json(householdPayload(userId));
  }

  const current = householdForUser(userId);
  if (current && current.id !== invite.household_id) {
    const others = db.prepare("SELECT COUNT(*) AS n FROM household_members WHERE household_id=? AND status='active' AND user_id!=?").get(current.id, userId).n;
    if (current.owner_id === userId && others > 0) {
      return res.status(409).json({ error: 'owner cannot leave household with members' });
    }
    db.prepare('DELETE FROM household_members WHERE household_id=? AND user_id=?').run(current.id, userId);
    if (current.owner_id === userId && others === 0) {
      db.prepare('DELETE FROM household_invites WHERE household_id=?').run(current.id);
      db.prepare('DELETE FROM households WHERE id=?').run(current.id);
    }
  }

  db.prepare('INSERT OR IGNORE INTO users(id) VALUES(?)').run(userId);
  db.prepare("INSERT OR REPLACE INTO household_members(household_id, user_id, role, status, created_at) VALUES(?, ?, 'member', 'active', datetime('now'))")
    .run(invite.household_id, userId);
  db.prepare("UPDATE household_invites SET status='accepted' WHERE code=?").run(invite.code);
  res.json(householdPayload(userId));
});

r.post('/leave', (req, res) => {
  const { userId } = req.body || {};
  if (!userId) return res.status(400).json({ error: 'userId required' });
  const hh = householdForUser(userId);
  if (!hh) return res.json({ ok: true });
  if (hh.owner_id === userId) return res.status(403).json({ error: 'owner cannot leave' });
  db.prepare('DELETE FROM household_members WHERE household_id=? AND user_id=?').run(hh.id, userId);
  res.json({ ok: true });
});

r.delete('/member/:memberId', (req, res) => {
  const userId = req.query.userId || req.body?.userId;
  const { memberId } = req.params;
  if (!userId || !memberId) return res.status(400).json({ error: 'userId, memberId required' });
  const hh = householdForUser(userId);
  if (!hh || hh.owner_id !== userId) return res.status(403).json({ error: 'owner only' });
  if (memberId === userId) return res.status(400).json({ error: 'cannot remove owner' });
  db.prepare('DELETE FROM household_members WHERE household_id=? AND user_id=?').run(hh.id, memberId);
  res.json(householdPayload(userId));
});

r.delete('/invite/:code', (req, res) => {
  const userId = req.query.userId || req.body?.userId;
  if (!userId) return res.status(400).json({ error: 'userId required' });
  const hh = householdForUser(userId);
  if (!hh || hh.owner_id !== userId) return res.status(403).json({ error: 'owner only' });
  db.prepare("UPDATE household_invites SET status='revoked' WHERE code=? AND household_id=?").run(String(req.params.code).toUpperCase(), hh.id);
  res.json(householdPayload(userId));
});

export default r;
