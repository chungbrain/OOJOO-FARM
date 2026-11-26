// model-manager 학습 엔진 단위 테스트 — OJP1 forward 패리티(Python 벡터), 직렬화, 기울기, 학습 수렴, 이미지 처리
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  parseModel, serializeModel, forward, backward, zerosLike, paramArrays, randomModel, adaptModel, softmax, rng,
} from '../src/ml/ojp1.js';
import { trainModel, evaluate, toInput } from '../src/ml/train.js';
import { decodeImage, encodeJpeg, exifOrientation, cropNormalized, resampleToFloat, fitWithin } from '../src/ml/image.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MODELS = path.resolve(HERE, '../models');
const VECTORS = path.resolve(HERE, '../../simulation/test');

const floats = (b64) => {
  const b = Buffer.from(b64, 'base64');
  return new Float32Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length));
};
const load = (id) => parseModel(fs.readFileSync(path.join(MODELS, `${id}.bin`)));

function assertLogits(got, want, tol, msg) {
  for (let i = 0; i < want.length; i++) {
    assert.ok(Math.abs(got[i] - want[i]) <= tol * Math.max(1, Math.abs(want[i])), `${msg} logit[${i}] ${got[i]} vs ${want[i]}`);
  }
}

test('forward 가 Python 학습 스크립트의 검증 벡터와 일치 (건강등급·해충·종별 14종)', (t) => {
  if (!fs.existsSync(VECTORS)) return t.skip('simulation 벡터 없음');
  for (const id of ['health_grade', 'pest']) {
    const v = JSON.parse(fs.readFileSync(path.join(VECTORS, `vectors_${id}.json`), 'utf8'));
    const m = load(id);
    for (const c of v.cases) assertLogits(forward(m, floats(c.input)), c.logits, 1e-3, id);
  }
  const sp = JSON.parse(fs.readFileSync(path.join(VECTORS, 'vectors_species.json'), 'utf8'));
  for (const [id, c] of Object.entries(sp.species)) assertLogits(forward(load(id), floats(c.input)), c.logits, 1e-3, id);
});

test('직렬화 왕복 — 바이트 동일, 잘못된 파일 거부', () => {
  const bytes = fs.readFileSync(path.join(MODELS, 'pest.bin'));
  assert.ok(serializeModel(parseModel(bytes)).equals(bytes));
  assert.throws(() => parseModel(Buffer.from('NOPE0000000000000000')), /magic/);
  assert.throws(() => parseModel(Buffer.concat([bytes, Buffer.from([0])])), /trailing/);
});

test('backward 기울기 = 수치 미분 (작은 모델)', () => {
  const m = randomModel({ size: 8, channels: [3, 4], nclass: 3, seed: 3 });
  const r = rng(5);
  const x = Float32Array.from({ length: 8 * 8 * 3 }, () => r());
  const y = 1;
  const loss = () => -Math.log(softmax(forward(m, x))[y]);
  const cache = {};
  const p = softmax(forward(m, x, cache));
  const d = Float32Array.from(p, (v, i) => v - (i === y ? 1 : 0));
  const grads = zerosLike(m);
  backward(m, cache, d, grads);

  const params = paramArrays(m);
  let checked = 0; let ok = 0;
  for (let a = 0; a < params.length; a++) {
    for (let i = 0; i < params[a].length; i += Math.max(1, Math.floor(params[a].length / 12))) {
      const old = params[a][i];
      const eps = 1e-2;
      params[a][i] = old + eps; const lp = loss();
      params[a][i] = old - eps; const lm = loss();
      params[a][i] = old;
      const num = (lp - lm) / (2 * eps);
      const ana = grads[a][i];
      if (Math.abs(num) < 1e-4 && Math.abs(ana) < 1e-4) continue;
      checked++;
      if (Math.abs(num - ana) <= 0.05 * Math.max(Math.abs(num), Math.abs(ana)) + 1e-4) ok++;
    }
  }
  assert.ok(checked >= 20, `checked ${checked}`);
  // ReLU/maxpool 꺾임점 근처 몇 개는 수치 미분이 어긋날 수 있다
  assert.ok(ok / checked >= 0.9, `${ok}/${checked} 일치`);
});

test('adaptModel: 기존 라벨 출력은 그대로, 새 라벨은 처음엔 거의 예측되지 않음', () => {
  const parent = load('pest');
  const labels = ['none', 'light', 'moderate', 'severe'];
  const m = adaptModel(parent, labels, [...labels, 'aphid']);
  const x = Float32Array.from({ length: 48 * 48 * 3 }, (_, i) => ((i * 37) % 255) / 255);
  const a = forward(parent, x);
  const b = forward(m, x);
  assert.equal(b.length, 5);
  for (let i = 0; i < 4; i++) assert.ok(Math.abs(a[i] - b[i]) < 1e-4);
  assert.ok(softmax(b)[4] < 0.5);
});

// 합성 사진: 초록 잎 / 갈변 잎 / 초록 잎+검은 반점 — 실제 사진은 아니지만 학습 루프가 동작하는지 확인
function synth(kind, r, w = 64, h = 64) {
  const data = new Uint8Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3;
      const n = (r() - 0.5) * 40;
      const leaf = kind === 'brown' ? [150 + n, 100 + n, 40] : [50 + n, 150 + n, 50 + n / 2];
      data[i] = leaf[0]; data[i + 1] = leaf[1]; data[i + 2] = leaf[2];
    }
  }
  if (kind === 'spots') {
    for (let s = 0; s < 14; s++) {
      const cx = r.int(w); const cy = r.int(h);
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
        const x = cx + dx; const y = cy + dy;
        if (x >= 0 && y >= 0 && x < w && y < h) data.fill(10, (y * w + x) * 3, (y * w + x) * 3 + 3);
      }
    }
  }
  return { width: w, height: h, data };
}

test('학습: 부모 conv 에서 새 3라벨 모델을 미세조정하면 검증 정확도가 오른다', () => {
  const labels = ['green', 'brown', 'spots'];
  const r = rng(11);
  const mk = (n) => labels.flatMap((k, y) => Array.from({ length: n }, () => ({ img: synth(k, r), y })));
  const train = mk(10);
  const val = mk(5).map(({ img, y }) => ({ x: toInput(img), y }));
  const init = adaptModel(load('health_grade'), [], labels);
  const before = evaluate(init, val, labels);
  const epochs = [];
  const { model, history } = trainModel({ init, train, val, labels, epochs: 8, seed: 3, onEpoch: (h) => epochs.push(h) });
  const after = evaluate(model, val, labels);
  assert.equal(history.length, 8);
  assert.equal(epochs.length, 8);
  assert.ok(after.accuracy >= 0.9, `after ${after.accuracy} (before ${before.accuracy}) ${JSON.stringify(history.map((h) => [h.trainAcc.toFixed(2), h.valAcc]))}`);
  assert.ok(after.macroF1 >= 0.9);
  assert.equal(after.confusion.flat().reduce((a, b) => a + b, 0), 15);
  // 학습 결과는 farmer 가 읽는 포맷 그대로
  const back = parseModel(serializeModel(model));
  assert.equal(back.nclass, 3);
});

test('평가 지표: 혼동행렬·클래스별 재현율·macro-F1', () => {
  const m = randomModel({ size: 8, channels: [2], nclass: 2, seed: 1 });
  const x = new Float32Array(8 * 8 * 3).fill(0.5);
  const pred = softmax(forward(m, x)).indexOf(Math.max(...softmax(forward(m, x))));
  const ev = evaluate(m, [{ x, y: pred }, { x, y: 1 - pred }], ['a', 'b']);
  assert.equal(ev.accuracy, 0.5);
  assert.equal(ev.perClass[pred].recall, 1);
  assert.equal(ev.perClass[1 - pred].recall, 0);
  assert.equal(ev.perClass[1 - pred].f1, 0);
});

test('이미지: JPEG 왕복, EXIF 회전, RoI 자르기, 축소', () => {
  const img = { width: 40, height: 20, data: new Uint8Array(40 * 20 * 3) };
  for (let y = 0; y < 20; y++) for (let x = 0; x < 40; x++) img.data.set(x < 20 ? [200, 30, 30] : [30, 30, 200], (y * 40 + x) * 3);
  const jpg = encodeJpeg(img, 95);
  const back = decodeImage(jpg);
  assert.deepEqual([back.width, back.height], [40, 20]);

  // APP1 Exif(Orientation=6, 시계방향 90°)를 SOI 뒤에 끼워 넣는다
  const tiff = Buffer.from([0x4d, 0x4d, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0]);
  const app1 = Buffer.concat([Buffer.from([0xff, 0xe1]), Buffer.alloc(2), Buffer.from('Exif\0\0', 'ascii'), tiff]);
  app1.writeUInt16BE(app1.length - 2, 2);
  const rotated = Buffer.concat([jpg.subarray(0, 2), app1, jpg.subarray(2)]);
  assert.equal(exifOrientation(rotated), 6);
  const up = decodeImage(rotated);
  assert.deepEqual([up.width, up.height], [20, 40]);
  // 왼쪽(빨강)이 위로 간다
  assert.ok(up.data[0] > 150 && up.data[2] < 100);

  const right = cropNormalized(back, { x: 0.5, y: 0, w: 0.5, h: 1 });
  assert.equal(right.width, 20);
  const f = resampleToFloat(right, 4, 4);
  assert.ok(f[2] > 0.6 && f[0] < 0.3); // 파랑
  assert.deepEqual([fitWithin(back, 10).width, fitWithin(back, 10).height], [10, 5]);
  assert.throws(() => decodeImage(Buffer.from('not an image')), /JPEG/);
});
