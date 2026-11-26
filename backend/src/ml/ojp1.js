// OJP1 초경량 CNN — 파싱 / 직렬화 / forward / backward (학습용).
//
// farmer(Android Ojp1.kt)·PC 시뮬레이션(simulation/src/models/ojp1.js)·Python 학습(ml/plant_health)과
// 같은 포맷·같은 연산이다. 여기서 학습해 저장한 .bin 을 farmer 가 그대로 읽는다.
//
// 포맷 (LITTLE_ENDIAN):
//   "OJP1"(4B) | int size | int nclass | int nLayers
//   per layer: byte type — 1=conv{int kh,kw,cin,cout; float w[kh*kw*cin*cout]; float b[cout]}
//                       — 2=dense{int inn,out; float w[inn*out]; float b[out]}
// 구조: [conv3x3(same) → ReLU → maxpool2] × N → GAP → dense
// 가중치 인덱스: conv w[((ky*kw+kx)*cin+ic)*cout+oc], dense w[i*out+o] (Kotlin 과 동일)

export function parseModel(bytes) {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  if (buf.length < 16) throw new Error('bad model file: too small');
  if (buf.toString('ascii', 0, 4) !== 'OJP1') throw new Error('bad model magic');
  const size = buf.readInt32LE(4);
  const nclass = buf.readInt32LE(8);
  const nLayers = buf.readInt32LE(12);
  let off = 16;
  const readFloats = (n) => {
    const a = new Float32Array(n);
    for (let j = 0; j < n; j++) { a[j] = buf.readFloatLE(off); off += 4; }
    return a;
  };
  const convs = [];
  let dense = null;
  for (let i = 0; i < nLayers; i++) {
    const type = buf.readUInt8(off); off += 1;
    if (type === 1) {
      const kh = buf.readInt32LE(off); const kw = buf.readInt32LE(off + 4);
      const cin = buf.readInt32LE(off + 8); const cout = buf.readInt32LE(off + 12);
      off += 16;
      const w = readFloats(kh * kw * cin * cout);
      const b = readFloats(cout);
      convs.push({ kh, kw, cin, cout, w, b });
    } else if (type === 2) {
      const inn = buf.readInt32LE(off); const out = buf.readInt32LE(off + 4);
      off += 8;
      const w = readFloats(inn * out);
      const b = readFloats(out);
      dense = { inn, out, w, b };
    } else {
      throw new Error(`bad model file: unknown layer type ${type}`);
    }
  }
  if (!dense) throw new Error('bad model file: no dense layer');
  if (off !== buf.length) throw new Error('bad model file: trailing bytes');
  if (dense.out !== nclass) throw new Error('bad model file: nclass != dense.out');
  return { size, nclass, convs, dense };
}

export function serializeModel(m) {
  let len = 16;
  for (const c of m.convs) len += 1 + 16 + (c.w.length + c.b.length) * 4;
  len += 1 + 8 + (m.dense.w.length + m.dense.b.length) * 4;
  const buf = Buffer.alloc(len);
  buf.write('OJP1', 0, 'ascii');
  buf.writeInt32LE(m.size, 4);
  buf.writeInt32LE(m.dense.out, 8);
  buf.writeInt32LE(m.convs.length + 1, 12);
  let off = 16;
  const writeFloats = (a) => { for (let j = 0; j < a.length; j++) { buf.writeFloatLE(a[j], off); off += 4; } };
  for (const c of m.convs) {
    buf.writeUInt8(1, off); off += 1;
    buf.writeInt32LE(c.kh, off); buf.writeInt32LE(c.kw, off + 4);
    buf.writeInt32LE(c.cin, off + 8); buf.writeInt32LE(c.cout, off + 12);
    off += 16;
    writeFloats(c.w); writeFloats(c.b);
  }
  buf.writeUInt8(2, off); off += 1;
  buf.writeInt32LE(m.dense.inn, off); buf.writeInt32LE(m.dense.out, off + 4);
  off += 8;
  writeFloats(m.dense.w); writeFloats(m.dense.b);
  return buf;
}

export function cloneModel(m) {
  return {
    size: m.size,
    nclass: m.nclass,
    convs: m.convs.map((c) => ({ ...c, w: Float32Array.from(c.w), b: Float32Array.from(c.b) })),
    dense: { ...m.dense, w: Float32Array.from(m.dense.w), b: Float32Array.from(m.dense.b) },
  };
}

/** 학습 대상 파라미터 배열 목록 (같은 순서로 grads/momentum 을 만든다). */
export function paramArrays(m) {
  const out = [];
  for (const c of m.convs) out.push(c.w, c.b);
  out.push(m.dense.w, m.dense.b);
  return out;
}

export function zerosLike(m) {
  return paramArrays(m).map((a) => new Float32Array(a.length));
}

// ---------- seedable RNG ----------

export function rng(seed = 1) {
  let s = seed >>> 0;
  const next = () => {
    s = (s + 0x6D2B79F5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  next.normal = () => {
    const u = Math.max(next(), 1e-12);
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * next());
  };
  next.int = (n) => Math.floor(next() * n);
  return next;
}

/** He 초기화 무작위 모델 (테스트·작은 모델용). */
export function randomModel({ size = 48, channels = [8, 16, 24], nclass, seed = 1 }) {
  const r = rng(seed);
  const convs = [];
  let cin = 3;
  for (const cout of channels) {
    const w = new Float32Array(9 * cin * cout);
    const std = Math.sqrt(2 / (9 * cin));
    for (let i = 0; i < w.length; i++) w[i] = r.normal() * std;
    convs.push({ kh: 3, kw: 3, cin, cout, w, b: new Float32Array(cout) });
    cin = cout;
  }
  const w = new Float32Array(cin * nclass);
  for (let i = 0; i < w.length; i++) w[i] = r.normal() * Math.sqrt(1 / cin);
  return { size, nclass, convs, dense: { inn: cin, out: nclass, w, b: new Float32Array(nclass) } };
}

/**
 * 부모 모델에서 새 라벨 집합의 모델을 만든다 — conv 는 그대로, dense 는 같은 라벨 id 의 열을 복사하고
 * 새 라벨 열은 작은 난수 + 부모 bias 최솟값(처음엔 거의 예측되지 않게)으로 시작.
 */
export function adaptModel(parent, parentLabels, labels, seed = 7) {
  const m = cloneModel(parent);
  const r = rng(seed);
  const inn = parent.dense.inn;
  const out = labels.length;
  const w = new Float32Array(inn * out);
  const b = new Float32Array(out);
  const minBias = Math.min(...parent.dense.b);
  labels.forEach((lab, o) => {
    const p = parentLabels.indexOf(lab);
    for (let i = 0; i < inn; i++) {
      w[i * out + o] = p >= 0 ? parent.dense.w[i * parent.dense.out + p] : r.normal() * 0.05;
    }
    b[o] = p >= 0 ? parent.dense.b[p] : minBias;
  });
  m.dense = { inn, out, w, b };
  m.nclass = out;
  return m;
}

// ---------- forward / backward ----------

function convRelu(src, h, w, cin, L) {
  const { kh, kw, cout } = L;
  const out = new Float32Array(h * w * cout);
  const pad = (kh / 2) | 0;
  const W = L.w;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * cout;
      for (let oc = 0; oc < cout; oc++) out[o + oc] = L.b[oc];
      for (let ky = 0; ky < kh; ky++) {
        const iy = y + ky - pad;
        if (iy < 0 || iy >= h) continue;
        for (let kx = 0; kx < kw; kx++) {
          const ix = x + kx - pad;
          if (ix < 0 || ix >= w) continue;
          const si = (iy * w + ix) * cin;
          const wb = (ky * kw + kx) * cin;
          for (let ic = 0; ic < cin; ic++) {
            const s = src[si + ic];
            if (s === 0) continue;
            const base = (wb + ic) * cout;
            for (let oc = 0; oc < cout; oc++) out[o + oc] += s * W[base + oc];
          }
        }
      }
      for (let oc = 0; oc < cout; oc++) if (out[o + oc] < 0) out[o + oc] = 0;
    }
  }
  return out;
}

function maxPool2(src, h, w, c) {
  const oh = h >> 1;
  const ow = w >> 1;
  const out = new Float32Array(oh * ow * c);
  const idx = new Int32Array(oh * ow * c);
  for (let y = 0; y < oh; y++) {
    for (let x = 0; x < ow; x++) {
      for (let ch = 0; ch < c; ch++) {
        let m = -Infinity;
        let mi = 0;
        for (let dy = 0; dy < 2; dy++) {
          for (let dx = 0; dx < 2; dx++) {
            const i = ((y * 2 + dy) * w + (x * 2 + dx)) * c + ch;
            if (src[i] > m) { m = src[i]; mi = i; }
          }
        }
        const o = (y * ow + x) * c + ch;
        out[o] = m;
        idx[o] = mi;
      }
    }
  }
  return { out, idx };
}

/**
 * rgb: Float32Array(size*size*3) 0..1 (H,W,C) → logits.
 * cache 객체를 주면 backward 에 필요한 중간값을 채운다.
 */
export function forward(m, rgb, cache = null) {
  let maps = rgb;
  let h = m.size;
  let w = m.size;
  let c = 3;
  const layers = cache ? [] : null;
  for (const L of m.convs) {
    const act = convRelu(maps, h, w, c, L);
    const { out, idx } = maxPool2(act, h, w, L.cout);
    if (layers) layers.push({ input: maps, h, w, cin: c, act, idx });
    maps = out;
    c = L.cout;
    h >>= 1;
    w >>= 1;
  }
  const plane = h * w;
  const gap = new Float32Array(c);
  for (let p = 0; p < plane; p++) for (let ch = 0; ch < c; ch++) gap[ch] += maps[p * c + ch];
  for (let ch = 0; ch < c; ch++) gap[ch] /= plane;
  const D = m.dense;
  const logits = new Float32Array(D.out);
  for (let o = 0; o < D.out; o++) logits[o] = D.b[o];
  for (let i = 0; i < D.inn; i++) {
    const g = gap[i];
    for (let o = 0; o < D.out; o++) logits[o] += g * D.w[i * D.out + o];
  }
  if (cache) {
    cache.layers = layers;
    cache.gap = gap;
    cache.last = { h, w, c };
  }
  return logits;
}

/** forward(cache) 후 dLogits 를 받아 grads(zerosLike 순서)에 누적. */
export function backward(m, cache, dLogits, grads) {
  const D = m.dense;
  const nConv = m.convs.length;
  const gDw = grads[nConv * 2];
  const gDb = grads[nConv * 2 + 1];
  const dGap = new Float32Array(D.inn);
  for (let i = 0; i < D.inn; i++) {
    const g = cache.gap[i];
    let acc = 0;
    for (let o = 0; o < D.out; o++) {
      gDw[i * D.out + o] += g * dLogits[o];
      acc += D.w[i * D.out + o] * dLogits[o];
    }
    dGap[i] = acc;
  }
  for (let o = 0; o < D.out; o++) gDb[o] += dLogits[o];

  const { h: lh, w: lw, c: lc } = cache.last;
  const plane = lh * lw;
  let dMaps = new Float32Array(plane * lc);
  for (let p = 0; p < plane; p++) for (let ch = 0; ch < lc; ch++) dMaps[p * lc + ch] = dGap[ch] / plane;

  for (let l = nConv - 1; l >= 0; l--) {
    const L = m.convs[l];
    const { input, h, w, cin, act, idx } = cache.layers[l];
    const { kh, kw, cout } = L;
    const dAct = new Float32Array(h * w * cout);
    for (let j = 0; j < dMaps.length; j++) dAct[idx[j]] += dMaps[j];
    for (let k = 0; k < dAct.length; k++) if (act[k] <= 0) dAct[k] = 0;

    const gW = grads[l * 2];
    const gB = grads[l * 2 + 1];
    const dInput = l > 0 ? new Float32Array(h * w * cin) : null;
    const pad = (kh / 2) | 0;
    const W = L.w;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const o = (y * w + x) * cout;
        let any = false;
        for (let oc = 0; oc < cout; oc++) {
          if (dAct[o + oc] !== 0) { any = true; gB[oc] += dAct[o + oc]; }
        }
        if (!any) continue;
        for (let ky = 0; ky < kh; ky++) {
          const iy = y + ky - pad;
          if (iy < 0 || iy >= h) continue;
          for (let kx = 0; kx < kw; kx++) {
            const ix = x + kx - pad;
            if (ix < 0 || ix >= w) continue;
            const si = (iy * w + ix) * cin;
            const wb = (ky * kw + kx) * cin;
            for (let ic = 0; ic < cin; ic++) {
              const s = input[si + ic];
              const base = (wb + ic) * cout;
              let acc = 0;
              for (let oc = 0; oc < cout; oc++) {
                const g = dAct[o + oc];
                gW[base + oc] += s * g;
                acc += W[base + oc] * g;
              }
              if (dInput) dInput[si + ic] += acc;
            }
          }
        }
      }
    }
    dMaps = dInput;
  }
}

export function softmax(logits) {
  let max = -Infinity;
  for (const v of logits) if (v > max) max = v;
  const e = Array.from(logits, (v) => Math.exp(v - max));
  const s = e.reduce((a, b) => a + b, 0);
  return e.map((v) => v / s);
}

export function predict(m, rgb) {
  const p = softmax(forward(m, rgb));
  let best = 0;
  for (let i = 1; i < p.length; i++) if (p[i] > p[best]) best = i;
  return { index: best, confidence: p[best], probs: p };
}
