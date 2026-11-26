// model-manager 작업 큐 — 한 번에 하나씩 worker thread 에서 학습/평가를 돌리고 결과를 레지스트리에 기록한다.
import { Worker } from 'node:worker_threads';
import fs from 'node:fs';
import path from 'node:path';
import { nanoid } from 'nanoid';
import db from '../db.js';
import {
  modelRow, labelsMeta, activeVersion, versionRow, TRAINED_DIR, SAMPLE_DIR, sha256, parseJson,
} from './registry.js';

let running = null; // { id, worker }
const listeners = new Set();

// 이전 프로세스에서 돌던 작업은 실패 처리 (대기 중 작업은 이어서 실행)
db.prepare("UPDATE ml_jobs SET status='failed', message='서버 재시작으로 중단됨', finished_at=datetime('now') WHERE status='running'").run();

export function jobRow(id) {
  const j = db.prepare('SELECT * FROM ml_jobs WHERE id=?').get(id);
  return j ? toJob(j) : null;
}

export function toJob(j) {
  return {
    id: j.id,
    modelId: j.model_id,
    type: j.type,
    status: j.status,
    progress: j.progress,
    message: j.message,
    params: parseJson(j.params_json, {}),
    result: parseJson(j.result_json, null),
    createdAt: j.created_at,
    startedAt: j.started_at,
    finishedAt: j.finished_at,
  };
}

export function recentJobs(modelId, limit = 10) {
  return db.prepare('SELECT * FROM ml_jobs WHERE model_id=? ORDER BY created_at DESC, rowid DESC LIMIT ?').all(modelId, limit).map(toJob);
}

export function activeJobs() {
  return db.prepare("SELECT * FROM ml_jobs WHERE status IN ('queued','running') ORDER BY created_at ASC, rowid ASC").all().map(toJob);
}

/** 작업 종료 콜백 (테스트/알림용) */
export function onJobFinished(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function enqueueJob(type, modelId, params = {}) {
  const id = nanoid(12);
  db.prepare("INSERT INTO ml_jobs(id, model_id, type, status, params_json, message) VALUES(?,?,?,'queued',?,?)")
    .run(id, modelId, type, JSON.stringify(params), '대기 중');
  setImmediate(pump);
  return id;
}

// rowid: 사진이 들어온 순서 — 버전이 어떤 사진까지 보고 학습했는지(maxSampleRowid) 구분하는 데 쓴다
function samplesFor(modelId, labels) {
  const rows = db.prepare('SELECT rowid AS rid, * FROM ml_samples WHERE model_id=?').all(modelId);
  return rows.filter((s) => labels.includes(s.label)).map((s) => ({
    path: path.join(SAMPLE_DIR, modelId, s.file),
    label: s.label,
    split: s.split,
    rowid: s.rid,
  }));
}

/** 이 버전이 학습에 본 마지막 사진 rowid (앱 내장 버전은 0 — 모든 사진이 처음 보는 사진) */
export function trainedUpTo(v) {
  return parseJson(v?.train_json, {})?.maxSampleRowid ?? 0;
}

function buildWorkerData(job) {
  const model = modelRow(job.model_id);
  if (!model) throw new Error('모델이 없습니다');
  const labels = labelsMeta(model).map((l) => l.id);
  const params = parseJson(job.params_json, {});
  if (job.type === 'train') {
    // 기본은 현재 배포 버전에서 이어서 학습. 'base' 이거나 아직 배포 버전이 없는 새 모델이면 기반 모델에서 시작
    const active = activeVersion(model.id);
    const base = model.base_model ? activeVersion(model.base_model) : null;
    const parent = (params.from === 'base' || !active) ? (base || active) : active;
    if (!parent) throw new Error('시작할 모델(배포 버전 또는 기반 모델)이 없습니다');
    const samples = samplesFor(model.id, labels);
    return {
      type: 'train',
      maxSampleRowid: samples.reduce((a, s) => Math.max(a, s.rowid), 0),
      parentPath: parent.path,
      parentLabels: parseJson(parent.labels_json, []),
      parentRef: { modelId: parent.model_id, version: parent.version },
      labels,
      samples,
      opts: {
        epochs: params.epochs,
        lr: params.lr,
        l2sp: params.l2sp,
        seed: params.seed ?? 7,
      },
    };
  }
  const v = params.version != null ? versionRow(model.id, params.version) : activeVersion(model.id);
  if (!v) throw new Error('평가할 버전이 없습니다');
  return {
    type: 'evaluate',
    version: v.version,
    modelPath: v.path,
    modelLabels: parseJson(v.labels_json, []),
    labels,
    since: trainedUpTo(v),
    samples: samplesFor(model.id, labels),
  };
}

function fail(id, message) {
  db.prepare("UPDATE ml_jobs SET status='failed', message=?, finished_at=datetime('now') WHERE id=?").run(String(message).slice(0, 500), id);
}

function finish(job, data, result) {
  const modelId = job.model_id;
  if (data.type === 'train') {
    const n = (db.prepare('SELECT MAX(version) AS v FROM ml_model_versions WHERE model_id=?').get(modelId).v ?? 0) + 1;
    const file = `${modelId}-v${n}.bin`;
    const dir = path.join(TRAINED_DIR, modelId);
    fs.mkdirSync(dir, { recursive: true });
    const full = path.join(dir, file);
    const bytes = Buffer.from(result.bytes);
    fs.writeFileSync(full, bytes);
    const parentSame = data.parentRef.modelId === modelId;
    db.prepare(`INSERT INTO ml_model_versions(id, model_id, version, source, file, path, sha256, size, labels_json,
      parent_version, train_json, metrics_json, baseline_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`).run(
      `${modelId}-v${n}`, modelId, n, 'trained', file, full, sha256(bytes), bytes.length, JSON.stringify(data.labels),
      parentSame ? data.parentRef.version : null,
      JSON.stringify({ ...result.train, ...parseJson(job.params_json, {}), from: data.parentRef, maxSampleRowid: data.maxSampleRowid }),
      JSON.stringify(result.metrics),
      JSON.stringify(result.baseline ? { ...result.baseline, from: data.parentRef } : null),
    );
    const summary = {
      version: n,
      accuracy: result.metrics?.accuracy ?? null,
      macroF1: result.metrics?.macroF1 ?? null,
      baselineAccuracy: result.baseline?.accuracy ?? null,
      baselineMacroF1: result.baseline?.macroF1 ?? null,
      nTrain: result.train.nTrain,
      nVal: result.train.nVal,
      seconds: result.train.seconds,
    };
    db.prepare("UPDATE ml_jobs SET status='done', progress=1, message=?, result_json=?, finished_at=datetime('now') WHERE id=?")
      .run(`v${n} 학습 완료`, JSON.stringify(summary), job.id);
  } else {
    const ev = { at: new Date().toISOString().replace('T', ' ').slice(0, 19), ...result };
    db.prepare('UPDATE ml_model_versions SET eval_json=? WHERE model_id=? AND version=?').run(JSON.stringify(ev), modelId, data.version);
    const summary = {
      version: data.version,
      valAccuracy: result.val?.accuracy ?? null,
      unseenAccuracy: result.unseen?.accuracy ?? null,
      allAccuracy: result.all?.accuracy ?? null,
      n: result.all?.n ?? 0,
    };
    db.prepare("UPDATE ml_jobs SET status='done', progress=1, message=?, result_json=?, finished_at=datetime('now') WHERE id=?")
      .run(`v${data.version} 평가 완료`, JSON.stringify(summary), job.id);
  }
}

function pump() {
  if (running) return;
  const job = db.prepare("SELECT * FROM ml_jobs WHERE status='queued' ORDER BY created_at ASC, rowid ASC LIMIT 1").get();
  if (!job) return;
  let data;
  try {
    data = buildWorkerData(job);
  } catch (e) {
    fail(job.id, e.message);
    listeners.forEach((fn) => fn(jobRow(job.id)));
    setImmediate(pump);
    return;
  }
  db.prepare("UPDATE ml_jobs SET status='running', started_at=datetime('now'), message='시작' WHERE id=?").run(job.id);
  const worker = new Worker(new URL('./worker.js', import.meta.url), { workerData: data });
  running = { id: job.id, worker };
  let settled = false;
  let lastWrite = 0;
  worker.on('message', (m) => {
    if (m.type === 'progress') {
      const now = Date.now();
      if (now - lastWrite > 300) {
        lastWrite = now;
        db.prepare('UPDATE ml_jobs SET progress=?, message=? WHERE id=?').run(m.progress, m.message, job.id);
      }
    } else if (m.type === 'done') {
      settled = true;
      try { finish(job, data, m.result); } catch (e) { fail(job.id, `결과 저장 실패: ${e.message}`); }
    } else if (m.type === 'error') {
      settled = true;
      fail(job.id, m.message);
    }
  });
  worker.on('error', (e) => { if (!settled) { settled = true; fail(job.id, e.message); } });
  worker.on('exit', (code) => {
    if (!settled) fail(job.id, `작업이 비정상 종료됨 (${code})`);
    running = null;
    listeners.forEach((fn) => fn(jobRow(job.id)));
    setImmediate(pump);
  });
}

// 서버 시작 시 남아 있던 대기 작업 실행
setImmediate(pump);
