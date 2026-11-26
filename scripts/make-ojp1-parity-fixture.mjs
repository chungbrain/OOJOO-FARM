#!/usr/bin/env node
// model-manager(백엔드 JS)로 학습한 OJP1 모델을 farmer(Kotlin Ojp1)가 똑같이 계산하는지 확인하는 fixture 생성.
//   android/slave/app/src/test/resources/trained_parity.bin        — JS 로 미세조정한 모델 (pest + 새 라벨 aphid)
//   android/slave/app/src/test/resources/trained_parity.json       — 입력(base64 float32)과 JS forward 로짓
// Kotlin 쪽: android/slave/.../detect/TrainedModelParityTest.kt
// 학습 코드(backend/src/ml)를 바꿨으면 다시 실행:  node scripts/make-ojp1-parity-fixture.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { parseModel, serializeModel, adaptModel, forward, rng } = await import(`file://${ROOT}/backend/src/ml/ojp1.js`);
const { trainModel } = await import(`file://${ROOT}/backend/src/ml/train.js`);
const OUT = path.join(ROOT, 'android/slave/app/src/test/resources');

const parentLabels = ['none', 'light', 'moderate', 'severe'];
const labels = [...parentLabels, 'aphid'];
const parent = parseModel(fs.readFileSync(path.join(ROOT, 'backend/models/pest.bin')));
const r = rng(5);
const img = (kind) => {
  const w = 64; const h = 64; const data = new Uint8Array(w * h * 3);
  for (let i = 0; i < w * h; i++) { const n = (r() - 0.5) * 30; data.set([50 + n, 150 + n, 50], i * 3); }
  const dots = kind === 'none' ? 0 : 25;
  for (let k = 0; k < dots; k++) {
    const x = r.int(w); const y = r.int(h);
    data.set(kind === 'aphid' ? [235, 235, 200] : [12, 10, 8], (y * w + x) * 3);
  }
  return { width: w, height: h, data };
};
const train = [];
for (const [y, kind] of [[0, 'none'], [3, 'severe'], [4, 'aphid']]) for (let i = 0; i < 6; i++) train.push({ img: img(kind), y });
const { model } = trainModel({ init: adaptModel(parent, parentLabels, labels), train, labels, epochs: 2, seed: 9 });

const bytes = serializeModel(model);
const cases = [0, 1, 2].map((c) => {
  const x = Float32Array.from({ length: 48 * 48 * 3 }, () => r());
  if (c === 1) for (let i = 0; i < x.length; i += 3) { x[i] = 0.2; x[i + 1] = 0.6; x[i + 2] = 0.2; }
  return { input: Buffer.from(x.buffer).toString('base64'), logits: Array.from(forward(model, x)) };
});
fs.writeFileSync(path.join(OUT, 'trained_parity.bin'), bytes);
fs.writeFileSync(path.join(OUT, 'trained_parity.json'), `${JSON.stringify({ labels, tolerance: 1e-3, cases }, null, 1)}\n`);
console.log(`wrote trained_parity.bin (${bytes.length} bytes, ${labels.length} outputs) + ${cases.length} cases`);
