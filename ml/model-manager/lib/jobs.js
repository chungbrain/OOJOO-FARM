// 로컬 학습/평가 작업 큐 — 한 번에 하나씩 worker thread 에서 돌리고 결과를 레지스트리(ml/registry)에 쓴다.
import { Worker } from 'node:worker_threads';
import { randomBytes } from 'node:crypto';
import * as store from './store.js';

const jobs = new Map(); // id -> job
const queue = [];
let running = null;

const now = () => new Date().toISOString().slice(0, 19).replace('T', ' ');

export function getJob(id) {
  return jobs.get(id) || null;
}

export function jobsFor(modelId) {
  return [...jobs.values()].filter((j) => j.modelId === modelId).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 10);
}

export function activeJobs() {
  return [...jobs.values()].filter((j) => j.status === 'queued' || j.status === 'running');
}

export function enqueue(type, modelId, params = {}) {
  const job = { id: randomBytes(6).toString('hex'), type, modelId, params, status: 'queued', progress: 0, message: '대기 중', result: null, createdAt: now() };
  jobs.set(job.id, job);
  queue.push(job.id);
  setImmediate(pump);
  return job;
}

/** 사진 목록 → worker 입력. seq: 사진이 들어온 순서 (버전이 어디까지 보고 학습했는지 = train.maxSampleSeq) */
function samplesFor(modelId, labels) {
  return store.samples(modelId).filter((s) => labels.includes(s.label)).map((s) => ({
    path: store.samplePath(modelId, s.sha256), label: s.label, split: s.split, rowid: s.seq,
    sha256: s.sha256, source: s.source, group: s.group,
  }));
}

/** 시작 모델: params.from = 버전 번호(이 모델) | 'base'(기반 모델의 최신 버전). 기본은 최신 버전. */
export function resolveParent(model, from) {
  if (from === 'base' || !store.latestVersion(model.id)) {
    const base = model.baseModel && store.latestVersion(model.baseModel);
    if (base) return { modelId: model.baseModel, v: base };
    if (from === 'base') return null;
  }
  const v = Number.isInteger(from) ? store.getVersion(model.id, from) : store.latestVersion(model.id);
  return v ? { modelId: model.id, v } : null;
}

function buildWorkerData(job) {
  const model = store.getModel(job.modelId);
  if (!model) throw new Error('모델이 없습니다');
  const labels = model.labels.map((l) => l.id);
  if (job.type === 'train') {
    const parent = resolveParent(model, job.params.from);
    if (!parent) throw new Error('시작할 모델 버전이 없습니다');
    const samples = samplesFor(model.id, labels);
    return {
      type: 'train',
      parentRef: { modelId: parent.modelId, version: parent.v.version },
      parentPath: store.versionModelPath(parent.modelId, parent.v),
      parentLabels: parent.v.labels,
      labels,
      samples,
      maxSampleSeq: samples.reduce((a, s) => Math.max(a, s.rowid || 0), 0),
      opts: { epochs: job.params.epochs, lr: job.params.lr, seed: job.params.seed ?? 7 },
    };
  }
  const v = job.params.version != null ? store.getVersion(model.id, job.params.version) : store.latestVersion(model.id);
  if (!v) throw new Error('평가할 버전이 없습니다');
  return {
    type: 'evaluate',
    version: v.version,
    modelPath: store.versionModelPath(model.id, v),
    modelLabels: v.labels,
    labels,
    since: v.train?.maxSampleSeq ?? 0,
    samples: samplesFor(model.id, labels),
  };
}

function finish(job, data, result) {
  if (data.type === 'train') {
    const used = new Set(data.samples.map((s) => s.sha256));
    const v = store.saveTrainedVersion(job.modelId, {
      bytes: result.bytes,
      labels: data.labels,
      parent: data.parentRef,
      metrics: result.metrics,
      baseline: result.baseline ? { ...result.baseline, from: data.parentRef } : null,
      train: { ...result.train, epochs: job.params.epochs ?? 15, lr: job.params.lr ?? 0.01, from: data.parentRef, maxSampleSeq: data.maxSampleSeq },
      dataset: data.samples.filter((s) => used.has(s.sha256)).map((s) => ({ sha256: s.sha256, label: s.label, split: s.split, source: s.source, group: s.group })),
    });
    job.result = {
      version: v.version,
      accuracy: result.metrics?.accuracy ?? null,
      macroF1: result.metrics?.macroF1 ?? null,
      baselineAccuracy: result.baseline?.accuracy ?? null,
      baselineMacroF1: result.baseline?.macroF1 ?? null,
      nTrain: result.train.nTrain,
      nVal: result.train.nVal,
      seconds: result.train.seconds,
    };
    job.message = `v${v.version} 학습 완료`;
  } else {
    store.saveEval(job.modelId, data.version, { at: now(), ...result });
    job.result = {
      version: data.version,
      valAccuracy: result.val?.accuracy ?? null,
      unseenAccuracy: result.unseen?.accuracy ?? null,
      allAccuracy: result.all?.accuracy ?? null,
      n: result.all?.n ?? 0,
    };
    job.message = `v${data.version} 평가 완료`;
  }
  job.status = 'done';
  job.progress = 1;
  job.finishedAt = now();
}

function fail(job, message) {
  job.status = 'failed';
  job.message = String(message).slice(0, 500);
  job.finishedAt = now();
}

function pump() {
  if (running || !queue.length) return;
  const job = jobs.get(queue.shift());
  let data;
  try {
    data = buildWorkerData(job);
  } catch (e) {
    fail(job, e.message);
    setImmediate(pump);
    return;
  }
  job.status = 'running';
  job.startedAt = now();
  job.message = '시작';
  const worker = new Worker(new URL('./worker.js', import.meta.url), { workerData: data });
  running = job;
  let settled = false;
  worker.on('message', (m) => {
    if (m.type === 'progress') { job.progress = m.progress; job.message = m.message; }
    else if (m.type === 'done') { settled = true; try { finish(job, data, m.result); } catch (e) { fail(job, `결과 저장 실패: ${e.message}`); } }
    else if (m.type === 'error') { settled = true; fail(job, m.message); }
  });
  worker.on('error', (e) => { if (!settled) { settled = true; fail(job, e.message); } });
  worker.on('exit', (code) => {
    if (!settled) fail(job, `작업이 비정상 종료됨 (${code})`);
    running = null;
    setImmediate(pump);
  });
}

/** 테스트용 — 작업이 끝날 때까지 기다린다 */
export async function waitFor(id, timeoutMs = 120000) {
  const t0 = Date.now();
  for (;;) {
    const j = jobs.get(id);
    if (!j || j.status === 'done' || j.status === 'failed') return j;
    if (Date.now() - t0 > timeoutMs) throw new Error('job timeout');
    await new Promise((r) => setTimeout(r, 100));
  }
}
