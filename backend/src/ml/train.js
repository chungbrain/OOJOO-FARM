// OJP1 미세조정 학습 + 평가 (순수 함수 — worker 와 테스트가 같이 쓴다).
//
// 학습 방식 (ml/plant_health/finetune_*.py 레시피와 같은 계열):
//  - 부모(현재 배포) 모델에서 시작, 새 라벨은 dense 열만 새로 (adaptModel)
//  - 증강: 무작위 자르기(각 변 85~100%) → 48×48 면적평균 축소, 좌우 반전, 밝기 ×0.9~1.1, 노이즈 σ0.012
//  - SGD + 모멘텀, 미니배치 16, 에폭마다 lr ×0.9, 전역 노름 클리핑
//  - L2-SP: 부모 가중치에서 멀어지는 만큼 벌점 → 사진이 적은 라벨에서 기존 지식을 잃지 않게
//  - 라벨 불균형은 클래스 가중치(역빈도, 0.5~3)로
//  - 검증 macro-F1 이 가장 좋은 에폭의 가중치를 결과로 (검증 샘플 없으면 마지막 에폭)
import { forward, backward, zerosLike, paramArrays, cloneModel, softmax, rng as makeRng } from './ojp1.js';
import { resampleToFloat } from './image.js';

export const INPUT = 48;

/** 검증/평가용 입력 — 이미지 전체를 48×48 로 (farmer 와 같은 전처리). */
export function toInput(img, size = INPUT) {
  return resampleToFloat(img, size, size);
}

export function augment(img, r, size = INPUT) {
  const sw = img.width * (0.85 + 0.15 * r());
  const sh = img.height * (0.85 + 0.15 * r());
  const sx = (img.width - sw) * r();
  const sy = (img.height - sh) * r();
  const x = resampleToFloat(img, size, size, sx, sy, sw, sh);
  const flip = r() < 0.5;
  const gain = 0.9 + 0.2 * r();
  const out = new Float32Array(x.length);
  for (let yy = 0; yy < size; yy++) {
    for (let xx = 0; xx < size; xx++) {
      const s = (yy * size + (flip ? size - 1 - xx : xx)) * 3;
      const d = (yy * size + xx) * 3;
      for (let c = 0; c < 3; c++) {
        const v = x[s + c] * gain + r.normal() * 0.012;
        out[d + c] = v < 0 ? 0 : v > 1 ? 1 : v;
      }
    }
  }
  return out;
}

/**
 * @param items [{x: Float32Array, y: number}]
 * @param mapPred 모델 출력 index → 평가 라벨 index (부모 모델 비교용)
 */
export function evaluate(model, items, labels, mapPred = (i) => i) {
  const K = labels.length;
  const confusion = Array.from({ length: K }, () => new Array(K).fill(0));
  let correct = 0;
  let confSum = 0;
  for (const it of items) {
    const p = softmax(forward(model, it.x));
    let best = 0;
    for (let i = 1; i < p.length; i++) if (p[i] > p[best]) best = i;
    const pred = mapPred(best);
    confSum += p[best];
    if (pred >= 0) confusion[it.y][pred]++;
    if (pred === it.y) correct++;
  }
  const perClass = labels.map((label, k) => {
    const support = confusion[k].reduce((a, b) => a + b, 0);
    const predicted = confusion.reduce((a, row) => a + row[k], 0);
    const tp = confusion[k][k];
    const precision = predicted ? tp / predicted : null;
    const recall = support ? tp / support : null;
    const f1 = precision && recall ? (2 * precision * recall) / (precision + recall) : support ? 0 : null;
    return { label, support, precision, recall, f1 };
  });
  const scored = perClass.filter((c) => c.support > 0);
  const n = items.length;
  return {
    n,
    accuracy: n ? correct / n : null,
    macroF1: scored.length ? scored.reduce((a, c) => a + c.f1, 0) / scored.length : null,
    avgConfidence: n ? confSum / n : null,
    perClass,
    confusion,
    labels,
  };
}

export function classWeights(ys, K) {
  const counts = new Array(K).fill(0);
  for (const y of ys) counts[y]++;
  const present = counts.filter((c) => c > 0).length || 1;
  return counts.map((c) => (c ? Math.min(3, Math.max(0.5, ys.length / (present * c))) : 1));
}

/**
 * @param init 시작 가중치 (adaptModel 로 라벨을 맞춘 부모) — L2-SP 의 기준점이기도 하다
 * @param train [{img, y}]  원본 이미지(증강은 매 에폭 새로)
 * @param val   [{x, y}]    48×48 입력
 */
export function trainModel({
  init, train, val = [], labels,
  epochs = 15, lr = 0.01, batch = null, momentum = 0.9, l2sp = 0.005, clip = 5,
  seed = 1, onEpoch = () => {},
}) {
  if (!train.length) throw new Error('학습 샘플이 없습니다');
  // 사진이 적으면 배치를 줄여 에폭마다 최소 8번은 갱신되게 (기본 16)
  batch = batch ?? Math.max(4, Math.min(16, Math.ceil(train.length / 8)));
  const r = makeRng(seed);
  const model = cloneModel(init);
  const params = paramArrays(model);
  const anchor = paramArrays(init).map((a) => Float32Array.from(a));
  const vel = zerosLike(model);
  const K = labels.length;
  const cw = classWeights(train.map((t) => t.y), K);
  const history = [];
  let best = null;
  let rate = lr;
  const order = train.map((_, i) => i);

  for (let ep = 1; ep <= epochs; ep++) {
    for (let i = order.length - 1; i > 0; i--) {
      const j = r.int(i + 1);
      [order[i], order[j]] = [order[j], order[i]];
    }
    let lossSum = 0;
    let hit = 0;
    for (let s = 0; s < order.length; s += batch) {
      const idx = order.slice(s, s + batch);
      const grads = zerosLike(model);
      for (const k of idx) {
        const { img, y } = train[k];
        const x = augment(img, r);
        const cache = {};
        const p = softmax(forward(model, x, cache));
        lossSum += -Math.log(Math.max(p[y], 1e-7)) * cw[y];
        let top = 0;
        for (let q = 1; q < K; q++) if (p[q] > p[top]) top = q;
        if (top === y) hit++;
        const d = new Float32Array(K);
        for (let q = 0; q < K; q++) d[q] = ((p[q] - (q === y ? 1 : 0)) * cw[y]) / idx.length;
        backward(model, cache, d, grads);
      }
      // L2-SP + 전역 노름 클리핑
      let norm = 0;
      for (let a = 0; a < grads.length; a++) {
        const g = grads[a]; const p = params[a]; const p0 = anchor[a];
        for (let i = 0; i < g.length; i++) {
          g[i] += l2sp * (p[i] - p0[i]);
          norm += g[i] * g[i];
        }
      }
      norm = Math.sqrt(norm);
      const scale = norm > clip ? clip / norm : 1;
      for (let a = 0; a < grads.length; a++) {
        const g = grads[a]; const v = vel[a]; const p = params[a];
        for (let i = 0; i < g.length; i++) {
          v[i] = momentum * v[i] - rate * g[i] * scale;
          p[i] += v[i];
        }
      }
    }
    rate *= 0.9;
    const ev = val.length ? evaluate(model, val, labels) : null;
    const score = ev ? (ev.macroF1 ?? 0) + 1e-3 * (ev.accuracy ?? 0) : ep;
    const h = { epoch: ep, loss: lossSum / train.length, trainAcc: hit / train.length, valAcc: ev?.accuracy ?? null, valF1: ev?.macroF1 ?? null };
    history.push(h);
    if (!best || score > best.score) best = { score, epoch: ep, model: cloneModel(model) };
    onEpoch(h, epochs);
  }
  return { model: best.model, bestEpoch: best.epoch, history };
}
