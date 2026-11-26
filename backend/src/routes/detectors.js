// 온디바이스 상태검출 모듈 API
//  - GET  /                          카탈로그 (모델 파일 sha256/크기 포함)
//  - GET  /models/:file              모델 파일 다운로드 (farmer, 카탈로그 파일만)
//  - GET  /plant/:plantId            식물의 모듈 목록 — 켜짐 여부·farmer 준비 상태·최신 결과 (master)
//  - POST /plant/:plantId/:moduleId  모듈 추가 (master) → farmer 에 detectors_sync 명령
//  - DELETE /plant/:plantId/:moduleId 모듈 삭제 (master) → farmer 에 detectors_sync 명령
//  - GET  /plant/:plantId/history    판독 결과 이력 (master)
//  - POST /results                   판독 결과 보고 (farmer, 세션키)
//  - POST /state                     모듈 모델 준비 상태 보고 (farmer, 세션키)
import { Router } from 'express';
import fs from 'node:fs';
import { nanoid } from 'nanoid';
import db from '../db.js';
import { slaveAuth } from '../middleware/auth.js';
import { notifySlaveCommand, notifyMasterEvent } from './commands.js';
import {
  catalog, servableModelFile, plantDetectors, detectorIds, DEFAULT_DETECTORS,
} from '../lib/detectors.js';

const r = Router();

// downloading: 모델 받는 중 / ready: 판독 가능 / error: 모델 준비 실패 /
// unsupported: 종 모델 없음(색 기반 추정으로 대체) / no_roi: 식물 RoI 가 없어 판독 대기
const STATES = new Set(['downloading', 'ready', 'error', 'unsupported', 'no_roi']);
const RESULTS_KEEP = 500; // 식물×모듈당 보관 결과 수
const MAX_DETAIL_BYTES = 2048;

const sqlNow = () => new Date().toISOString().replace('T', ' ').slice(0, 19);
const plantRow = (id) => db.prepare('SELECT * FROM plants WHERE id=?').get(id);

function parseJson(s) {
  if (!s) return null;
  try { return JSON.parse(s); } catch { return null; }
}

function toResult(row) {
  return {
    id: row.id,
    moduleId: row.module_id,
    label: row.label,
    confidence: row.confidence,
    trigger: row.trigger,
    modelSha: row.model_sha,
    detail: parseJson(row.detail_json),
    createdAt: row.created_at,
  };
}

// 서버가 가진 관수 기록으로 본 관수 간격 — farmer 가 아직 한 주기를 다 관측하지 못했어도 보여줄 수 있게.
function wateringCycle(plantId) {
  const rows = db.prepare('SELECT created_at FROM waterings WHERE plant_id=? ORDER BY created_at DESC LIMIT 11').all(plantId);
  if (!rows.length) return { lastWateredAt: null, avgIntervalHours: null, intervals: 0 };
  const ts = rows.map((w) => Date.parse(`${w.created_at.replace(' ', 'T')}Z`)).filter(Number.isFinite);
  const gaps = [];
  for (let i = 1; i < ts.length; i++) gaps.push((ts[i - 1] - ts[i]) / 3_600_000);
  const avg = gaps.length ? gaps.reduce((a, b) => a + b, 0) / gaps.length : null;
  return {
    lastWateredAt: rows[0].created_at,
    avgIntervalHours: avg == null ? null : Math.round(avg * 10) / 10,
    intervals: gaps.length,
  };
}

/** 카탈로그 전체 × 이 식물의 상태 — master 의 목록/추가·삭제 UI 가 그대로 그린다. */
function describePlant(row) {
  const enabled = new Map(plantDetectors(row).map((e) => [e.id, e]));
  const latestStmt = db.prepare(
    'SELECT * FROM detector_results WHERE plant_id=? AND module_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1',
  );
  const stateStmt = db.prepare('SELECT * FROM detector_states WHERE plant_id=? AND module_id=?');
  return catalog().map((m) => {
    const e = enabled.get(m.id);
    const st = stateStmt.get(row.id, m.id);
    const latest = latestStmt.get(row.id, m.id);
    const anySpecies = m.speciesModels ? Object.values(m.speciesModels)[0] : null;
    return {
      id: m.id,
      icon: m.icon,
      name: m.name,
      nameEn: m.nameEn,
      description: m.description,
      descriptionEn: m.descriptionEn,
      labels: m.labels,
      labelsKo: m.labelsKo,
      labelsEn: m.labelsEn,
      available: m.available,
      perSpecies: m.perSpecies,
      modelSize: m.model?.size ?? anySpecies?.size ?? null,
      enabled: Boolean(e),
      addedAt: e?.addedAt ?? null,
      // 다른 farmer 로 재배정된 식물이면 이전 farmer 의 준비 상태는 의미 없다
      state: e && st && st.slave_id === row.slave_id
        ? { state: st.state, detail: st.detail, modelSha: st.model_sha, updatedAt: st.updated_at }
        : null,
      latest: latest ? toResult(latest) : null,
      cycle: m.id === 'watering' ? wateringCycle(row.id) : null,
    };
  });
}

/** farmer 에 식물·모듈 재동기화 지시 — 대기 중인 같은 명령이 있으면 재사용(중복 적재 방지).
 *  모듈 추가/삭제뿐 아니라 RoI·farmer 배정 변경 때도 plants 라우트가 호출한다. */
export function queueDetectorSync(row) {
  if (!row.slave_id) return null;
  const existing = db.prepare(
    "SELECT * FROM commands WHERE slave_id=? AND action='detectors_sync' AND status='queued' ORDER BY created_at ASC LIMIT 1",
  ).get(row.slave_id);
  let command = existing;
  if (!command) {
    const id = nanoid(12);
    db.prepare('INSERT INTO commands(id, slave_id, plant_id, action, amount_ml, weather_factor) VALUES(?,?,?,?,?,?)')
      .run(id, row.slave_id, row.id, 'detectors_sync', 0, 1);
    command = db.prepare('SELECT * FROM commands WHERE id=?').get(id);
  }
  notifySlaveCommand(row.slave_id, command);
  return command.id;
}

function saveDetectors(plantId, list) {
  db.prepare('UPDATE plants SET detectors_json=? WHERE id=?').run(JSON.stringify(list), plantId);
}

// ---------- 카탈로그 / 모델 파일 ----------

r.get('/', (req, res) => {
  res.json({ modules: catalog(), defaults: DEFAULT_DETECTORS });
});

r.get('/models/:file', (req, res) => {
  const info = servableModelFile(req.params.file);
  if (!info) return res.status(404).json({ error: 'model not found' });
  const etag = `"${info.sha256}"`;
  res.set('ETag', etag);
  res.set('X-Model-Sha256', info.sha256);
  res.set('Cache-Control', 'public, max-age=300');
  if (req.get('if-none-match') === etag) return res.status(304).end();
  res.type('application/octet-stream');
  res.send(fs.readFileSync(info.path));
});

// ---------- master: 식물별 모듈 ----------

r.get('/plant/:plantId', (req, res) => {
  const row = plantRow(req.params.plantId);
  if (!row) return res.status(404).json({ error: 'plant not found' });
  res.json({ plantId: row.id, slaveId: row.slave_id, modules: describePlant(row) });
});

r.get('/plant/:plantId/history', (req, res) => {
  const row = plantRow(req.params.plantId);
  if (!row) return res.status(404).json({ error: 'plant not found' });
  const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 50, 1), 200);
  const moduleId = typeof req.query.module === 'string' ? req.query.module : null;
  if (moduleId && !detectorIds().includes(moduleId)) return res.status(400).json({ error: 'unknown module' });
  const rows = moduleId
    ? db.prepare('SELECT * FROM detector_results WHERE plant_id=? AND module_id=? ORDER BY created_at DESC, rowid DESC LIMIT ?').all(row.id, moduleId, limit)
    : db.prepare('SELECT * FROM detector_results WHERE plant_id=? ORDER BY created_at DESC, rowid DESC LIMIT ?').all(row.id, limit);
  res.json({ results: rows.map(toResult) });
});

r.post('/plant/:plantId/:moduleId', (req, res) => {
  const { plantId, moduleId } = req.params;
  if (!detectorIds().includes(moduleId)) return res.status(400).json({ error: 'unknown module' });
  const row = plantRow(plantId);
  if (!row) return res.status(404).json({ error: 'plant not found' });
  const list = plantDetectors(row);
  if (!list.some((e) => e.id === moduleId)) list.push({ id: moduleId, addedAt: sqlNow() });
  saveDetectors(row.id, list);
  const updated = plantRow(row.id);
  const commandId = queueDetectorSync(updated);
  res.json({ plantId: row.id, commandId, modules: describePlant(updated) });
});

r.delete('/plant/:plantId/:moduleId', (req, res) => {
  const { plantId, moduleId } = req.params;
  if (!detectorIds().includes(moduleId)) return res.status(400).json({ error: 'unknown module' });
  const row = plantRow(plantId);
  if (!row) return res.status(404).json({ error: 'plant not found' });
  saveDetectors(row.id, plantDetectors(row).filter((e) => e.id !== moduleId));
  db.prepare('DELETE FROM detector_states WHERE plant_id=? AND module_id=?').run(row.id, moduleId);
  const updated = plantRow(row.id);
  const commandId = queueDetectorSync(updated);
  res.json({ plantId: row.id, commandId, modules: describePlant(updated) });
});

// ---------- farmer: 결과 / 상태 보고 ----------

function cleanResult(x, labelsOf) {
  if (!x || typeof x !== 'object') return { error: 'each result must be an object' };
  const { moduleId, label, confidence = null, trigger = 'periodic', modelSha = null, detail = null } = x;
  if (!labelsOf.has(moduleId)) return { error: `unknown module: ${moduleId}` };
  if (!labelsOf.get(moduleId).includes(label)) return { error: `invalid label for ${moduleId}` };
  if (confidence !== null && !(typeof confidence === 'number' && confidence >= 0 && confidence <= 1)) {
    return { error: 'confidence must be a number in 0..1' };
  }
  if (typeof trigger !== 'string' || trigger.length > 30) return { error: 'trigger must be a string (max 30)' };
  if (modelSha !== null && (typeof modelSha !== 'string' || modelSha.length > 64)) return { error: 'modelSha must be a string (max 64)' };
  const detailJson = detail == null ? null : JSON.stringify(detail);
  if (detailJson && (typeof detail !== 'object' || Array.isArray(detail) || detailJson.length > MAX_DETAIL_BYTES)) {
    return { error: `detail must be an object (max ${MAX_DETAIL_BYTES} bytes)` };
  }
  return { moduleId, label, confidence, trigger, modelSha, detailJson };
}

r.post('/results', slaveAuth, (req, res) => {
  const { slaveId, plantId, results } = req.body;
  if (!plantId || !Array.isArray(results) || results.length === 0 || results.length > 20) {
    return res.status(400).json({ error: 'plantId and results[1..20] required' });
  }
  const row = plantRow(plantId);
  if (!row) return res.status(404).json({ error: 'plant not found' });
  if (row.slave_id !== slaveId) return res.status(403).json({ error: 'plant is not assigned to this farmer' });

  const labelsOf = new Map(catalog().map((m) => [m.id, m.labels]));
  const cleaned = [];
  for (const x of results) {
    const c = cleanResult(x, labelsOf);
    if (c.error) return res.status(400).json({ error: c.error });
    cleaned.push(c);
  }
  // 모듈 삭제 직후 도착한 결과(경합)는 버린다 — 꺼진 모듈의 결과가 다시 보이면 안 된다
  const enabled = new Set(plantDetectors(row).map((e) => e.id));
  const accepted = cleaned.filter((c) => enabled.has(c.moduleId));

  const ins = db.prepare(`INSERT INTO detector_results(id, slave_id, plant_id, module_id, label, confidence, trigger, model_sha, detail_json)
    VALUES(?,?,?,?,?,?,?,?,?)`);
  const prune = db.prepare(`DELETE FROM detector_results WHERE plant_id=? AND module_id=? AND id NOT IN (
    SELECT id FROM detector_results WHERE plant_id=? AND module_id=? ORDER BY created_at DESC, rowid DESC LIMIT ?)`);
  const out = [];
  db.exec('BEGIN');
  try {
    for (const c of accepted) {
      const id = nanoid(12);
      ins.run(id, slaveId, plantId, c.moduleId, c.label, c.confidence, c.trigger, c.modelSha, c.detailJson);
      prune.run(plantId, c.moduleId, plantId, c.moduleId, RESULTS_KEEP);
      out.push(toResult(db.prepare('SELECT * FROM detector_results WHERE id=?').get(id)));
    }
    db.exec('COMMIT');
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* noop */ }
    return res.status(500).json({ error: e.message });
  }
  if (out.length) notifyMasterEvent(slaveId, { type: 'detector_result', slaveId, plantId, results: out });
  res.json({ accepted: out.length, skipped: cleaned.length - out.length });
});

r.post('/state', slaveAuth, (req, res) => {
  const { slaveId, states } = req.body;
  if (!Array.isArray(states) || states.length === 0 || states.length > 100) {
    return res.status(400).json({ error: 'states[1..100] required' });
  }
  const ids = detectorIds();
  for (const s of states) {
    if (!s || typeof s !== 'object' || !s.plantId || !ids.includes(s.moduleId) || !STATES.has(s.state)) {
      return res.status(400).json({ error: `each state needs plantId, moduleId and state (${[...STATES].join('|')})` });
    }
    if (s.detail != null && (typeof s.detail !== 'string' || s.detail.length > 200)) {
      return res.status(400).json({ error: 'detail must be a string (max 200)' });
    }
  }
  const owned = new Set(db.prepare('SELECT id FROM plants WHERE slave_id=?').all(slaveId).map((p) => p.id));
  const up = db.prepare(`INSERT INTO detector_states(plant_id, module_id, slave_id, state, detail, model_sha, updated_at)
    VALUES(?,?,?,?,?,?,datetime('now'))
    ON CONFLICT(plant_id, module_id) DO UPDATE SET slave_id=excluded.slave_id, state=excluded.state,
      detail=excluded.detail, model_sha=excluded.model_sha, updated_at=excluded.updated_at`);
  let n = 0;
  for (const s of states) {
    if (!owned.has(s.plantId)) continue; // 다른 farmer 의 식물은 무시
    up.run(s.plantId, s.moduleId, slaveId, s.state, s.detail ?? null, typeof s.modelSha === 'string' ? s.modelSha.slice(0, 64) : null);
    n++;
  }
  res.json({ accepted: n });
});

export default r;
