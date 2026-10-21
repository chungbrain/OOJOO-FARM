// OJP1 초경량 모델 런타임 — PlantHealthNet.kt 의 파서/forward 를 JS 로 포팅.
// Android(slave)와 동일한 .bin 파일을 그대로 읽어, PC 시뮬레이션이
// 기기와 동일한 추론 결과(허용 오차 이내)를 내는지 검증한다.
//
// 포맷 (LITTLE_ENDIAN):
//   "OJP1"(4B) | int size | int nclass | int nLayers(4)
//   per layer: byte type — 1=conv{int kh,kw,cin,cout; float w[]; float b[cout]}
//                       — 2=dense{int inn,out; float w[inn*out]; float b[out]}

/** @param {Buffer|Uint8Array} bytes */
export function parseModel(bytes) {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  if (buf.length < 16) throw new Error('bad model file: too small');
  if (buf.toString('ascii', 0, 4) !== 'OJP1') throw new Error('bad model magic');
  const size = buf.readInt32LE(4);
  const nclass = buf.readInt32LE(8);
  const nLayers = buf.readInt32LE(12);
  let off = 16;
  const convs = [];
  let dense = null;
  for (let i = 0; i < nLayers; i++) {
    const type = buf.readUInt8(off); off += 1;
    if (type === 1) {
      const kh = buf.readInt32LE(off); off += 4;
      const kw = buf.readInt32LE(off); off += 4;
      const cin = buf.readInt32LE(off); off += 4;
      const cout = buf.readInt32LE(off); off += 4;
      const w = new Float32Array(kh * kw * cin * cout);
      for (let j = 0; j < w.length; j++) { w[j] = buf.readFloatLE(off); off += 4; }
      const b = new Float32Array(cout);
      for (let j = 0; j < b.length; j++) { b[j] = buf.readFloatLE(off); off += 4; }
      convs.push({ kh, kw, cin, cout, w, b });
    } else {
      const inn = buf.readInt32LE(off); off += 4;
      const out = buf.readInt32LE(off); off += 4;
      const w = new Float32Array(inn * out);
      for (let j = 0; j < w.length; j++) { w[j] = buf.readFloatLE(off); off += 4; }
      const b = new Float32Array(out);
      for (let j = 0; j < b.length; j++) { b[j] = buf.readFloatLE(off); off += 4; }
      dense = { inn, out, w, b };
    }
  }
  if (!dense) throw new Error('bad model file: no dense layer');
  if (off !== buf.length) throw new Error('bad model file: trailing bytes');
  return { size, nclass, convs, dense };
}

/** conv3x3 same-pad + ReLU. src/출력 모두 (H,W,C) 인터리브. */
function convRelu(src, h, w, cin, layer) {
  const out = new Float32Array(h * w * layer.cout);
  const pad = (layer.kh / 2) | 0;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      for (let oc = 0; oc < layer.cout; oc++) {
        let s = layer.b[oc];
        for (let ky = 0; ky < layer.kh; ky++) {
          const iy = y + ky - pad;
          if (iy < 0 || iy >= h) continue;
          for (let kx = 0; kx < layer.kw; kx++) {
            const ix = x + kx - pad;
            if (ix < 0 || ix >= w) continue;
            for (let ic = 0; ic < cin; ic++) {
              const wi = ((ky * layer.kw + kx) * cin + ic) * layer.cout + oc;
              const si = (iy * w + ix) * cin + ic;
              s += src[si] * layer.w[wi];
            }
          }
        }
        out[(y * w + x) * layer.cout + oc] = s > 0 ? s : 0;
      }
    }
  }
  return out;
}

/** 2x2 max pool. */
function maxPool2(src, h, w, c) {
  const oh = (h / 2) | 0;
  const ow = (w / 2) | 0;
  const out = new Float32Array(oh * ow * c);
  for (let y = 0; y < oh; y++) {
    for (let x = 0; x < ow; x++) {
      for (let ch = 0; ch < c; ch++) {
        let m = -Infinity;
        for (let dy = 0; dy <= 1; dy++) {
          for (let dx = 0; dx <= 1; dx++) {
            const v = src[((y * 2 + dy) * w + (x * 2 + dx)) * c + ch];
            if (v > m) m = v;
          }
        }
        out[(y * ow + x) * c + ch] = m;
      }
    }
  }
  return out;
}

/**
 * 순수 forward — rgb: Float32Array(size*size*3), 0..1 (H,W,C 인터리브).
 * 반환: Float32Array logits (모델 nclass 길이).
 */
export function forward(model, rgb) {
  let maps = rgb;
  let h = model.size;
  let w = model.size;
  let c = 3;
  for (const layer of model.convs) {
    maps = convRelu(maps, h, w, c, layer);
    c = layer.cout;
    maps = maxPool2(maps, h, w, c);
    h = (h / 2) | 0;
    w = (w / 2) | 0;
  }
  // GAP
  const gap = new Float32Array(c);
  const plane = h * w;
  for (let ch = 0; ch < c; ch++) {
    let s = 0;
    for (let i = 0; i < plane; i++) s += maps[i * c + ch];
    gap[ch] = s / plane;
  }
  // dense
  const d = model.dense;
  const logits = new Float32Array(d.out);
  for (let o = 0; o < d.out; o++) {
    let s = d.b[o];
    for (let i = 0; i < d.inn; i++) s += gap[i] * d.w[i * d.out + o];
    logits[o] = s;
  }
  return logits;
}

/** softmax + argmax — 추론 결과 조립 공통 헬퍼. */
export function argmaxSoftmax(logits) {
  let maxI = 0;
  let maxV = logits[0];
  let sum = 0;
  const exps = new Float64Array(logits.length);
  for (let i = 0; i < logits.length; i++) {
    exps[i] = Math.exp(logits[i]);
    sum += exps[i];
    if (logits[i] > maxV) { maxV = logits[i]; maxI = i; }
  }
  return { index: maxI, confidence: sum > 0 ? exps[maxI] / sum : 0 };
}
