// model-manager 작업 worker — 학습/평가는 CPU 를 오래 쓰므로 API 스레드와 분리해 돌린다.
// 입력(workerData)과 결과는 모두 직렬화 가능한 값만 — DB 접근은 메인 스레드(jobs.js)가 한다.
import { parentPort, workerData } from 'node:worker_threads';
import fs from 'node:fs';
import { parseModel, serializeModel, adaptModel } from '../../../backend/src/ml/ojp1.js';
import { decodeImage } from './image.js';
import { trainModel, evaluate, toInput } from './train.js';

const post = (m) => parentPort.postMessage(m);

/** samples: [{path, label, split, rowid}] → 읽을 수 있는 것만 {img, y, split, rowid} */
function loadSamples(samples, labels) {
  const out = [];
  let skipped = 0;
  samples.forEach((s, i) => {
    const y = labels.indexOf(s.label);
    if (y < 0) { skipped++; return; }
    try {
      out.push({ img: decodeImage(fs.readFileSync(s.path)), y, split: s.split, rowid: s.rowid });
    } catch {
      skipped++;
    }
    if (i % 25 === 0) post({ type: 'progress', progress: 0.05 * (i / samples.length), message: `사진 읽는 중 ${i}/${samples.length}` });
  });
  return { items: out, skipped };
}

function summarizeCounts(items, labels) {
  const c = Object.fromEntries(labels.map((l) => [l, { train: 0, val: 0 }]));
  for (const it of items) c[labels[it.y]][it.split === 'val' ? 'val' : 'train']++;
  return c;
}

function runTrain(d) {
  const parent = parseModel(fs.readFileSync(d.parentPath));
  const labels = d.labels;
  const { items, skipped } = loadSamples(d.samples, labels);
  const train = items.filter((s) => s.split !== 'val').map(({ img, y }) => ({ img, y }));
  const val = items.filter((s) => s.split === 'val').map(({ img, y }) => ({ x: toInput(img), y }));
  if (!train.length) throw new Error('학습용 사진이 없습니다');

  // 같은 검증셋에서 부모(현재 배포) 모델 성능 — 부모가 모르는 새 라벨 사진은 틀린 것으로 센다
  const parentMap = d.parentLabels.map((l) => labels.indexOf(l));
  const baseline = val.length ? evaluate(parent, val, labels, (i) => parentMap[i] ?? -1) : null;

  const init = adaptModel(parent, d.parentLabels, labels, d.opts.seed ?? 7);
  const t0 = Date.now();
  const { model, bestEpoch, history } = trainModel({
    init, train, val, labels, ...d.opts,
    onEpoch: (h, total) => post({
      type: 'progress',
      progress: 0.05 + (0.9 * h.epoch) / total,
      message: `에폭 ${h.epoch}/${total} · 손실 ${h.loss.toFixed(3)} · 학습 정확도 ${(h.trainAcc * 100).toFixed(0)}%`
        + (h.valAcc != null ? ` · 검증 ${(h.valAcc * 100).toFixed(0)}%` : ''),
    }),
  });
  const metrics = val.length ? { ...evaluate(model, val, labels), dataset: 'samples' } : null;
  const trainEval = evaluate(model, train.map((t) => ({ x: toInput(t.img), y: t.y })), labels);
  return {
    bytes: serializeModel(model),
    metrics,
    baseline,
    train: {
      nTrain: train.length,
      nVal: val.length,
      skipped,
      counts: summarizeCounts(items, labels),
      trainAccuracy: trainEval.accuracy,
      bestEpoch,
      history,
      seconds: Math.round((Date.now() - t0) / 100) / 10,
    },
  };
}

function runEvaluate(d) {
  const model = parseModel(fs.readFileSync(d.modelPath));
  const labels = d.labels;
  const map = d.modelLabels.map((l) => labels.indexOf(l));
  const { items, skipped } = loadSamples(d.samples, labels);
  const asInput = (arr) => arr.map(({ img, y }) => ({ x: toInput(img), y }));
  const ev = (arr) => (arr.length ? evaluate(model, asInput(arr), labels, (i) => map[i] ?? -1) : null);
  post({ type: 'progress', progress: 0.5, message: `평가 중 (${items.length}장)` });
  return {
    val: ev(items.filter((s) => s.split === 'val')),
    unseen: ev(items.filter((s) => s.rowid > d.since)),
    all: ev(items),
    skipped,
  };
}

try {
  const result = workerData.type === 'train' ? runTrain(workerData) : runEvaluate(workerData);
  post({ type: 'done', result });
} catch (e) {
  post({ type: 'error', message: e?.message || String(e) });
}
