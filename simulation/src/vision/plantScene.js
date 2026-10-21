// 합성 식물 장면 생성기 — ml/plant_health/grade_paint.py 와 같은 분포를 JS 로 재현.
// 등급(5단계)과 해충(4단계)은 독립 파라미터이며, 시드 기반으로 결정론적 재현이 가능하다.
// 시뮬레이션 카메라가 이 장면을 "촬영"하고, JevHealthNet/PestNet 이 판정한다.

export const GRADE_LABELS = ['very_healthy', 'healthy', 'fair', 'poor', 'very_poor'];
export const GRADE_KO = {
  very_healthy: '매우건강', healthy: '건강', fair: '보통', poor: '나쁨', very_poor: '매우나쁨',
};
export const PEST_LABELS = ['none', 'light', 'moderate', 'severe'];
export const PEST_KO = {
  none: '해충없음', light: '경미발생', moderate: '보통발생', severe: '심각발생',
};

// species.py SPECIES 팔레트 (동일 값)
export const SPECIES = {
  cherry_tomato: { ko: '방울토마토', leaf: [46, 140, 52], vein: [28, 92, 36], fruit: [214, 48, 48], soil: [92, 64, 42], shape: 'leaflet' },
  basil: { ko: '바질', leaf: [22, 102, 48], vein: [90, 46, 92], fruit: [70, 40, 80], soil: [80, 58, 40], shape: 'round' },
  cactus: { ko: '선인장', leaf: [96, 140, 88], vein: [70, 110, 70], fruit: [200, 80, 90], soil: [168, 140, 96], shape: 'column' },
  herb: { ko: '허브', leaf: [60, 128, 70], vein: [40, 88, 50], fruit: [90, 70, 40], soil: [88, 62, 44], shape: 'needle' },
  strawberry: { ko: '딸기', leaf: [40, 130, 55], vein: [24, 86, 38], fruit: [220, 40, 60], soil: [100, 70, 48], shape: 'serrated' },
  pepper: { ko: '고추', leaf: [28, 110, 42], vein: [18, 72, 30], fruit: [200, 36, 32], soil: [86, 60, 40], shape: 'long' },
  pumpkin: { ko: '호박', leaf: [70, 148, 48], vein: [48, 100, 32], fruit: [230, 140, 30], soil: [94, 66, 40], shape: 'lobe' },
  zucchini: { ko: '애호박', leaf: [86, 160, 62], vein: [50, 110, 40], fruit: [150, 180, 60], soil: [90, 64, 42], shape: 'lobe' },
};

// grade_paint.py GRADE_STYLE / PEST_STYLE (동일 값 — 반드시 Python 과 함께 갱신)
const GRADE_STYLE = {
  very_healthy: { leafMul: 1.70, leafScale: 1.10, tint: [0.85, 1.15, 0.82], wilt: 0, burn: 0.00, necrosis: 0.00 },
  healthy: { leafMul: 1.00, leafScale: 1.00, tint: [0.95, 1.05, 0.88], wilt: 0, burn: 0.00, necrosis: 0.00 },
  fair: { leafMul: 0.75, leafScale: 0.95, tint: [1.10, 0.92, 0.45], wilt: 3, burn: 0.00, necrosis: 0.00 },
  poor: { leafMul: 0.45, leafScale: 0.90, tint: [1.20, 0.68, 0.30], wilt: 8, burn: 0.30, necrosis: 0.05 },
  very_poor: { leafMul: 0.25, leafScale: 0.85, tint: [1.35, 0.45, 0.25], wilt: 12, burn: 0.50, necrosis: 0.16 },
};
const PEST_STYLE = {
  none: { holes: [0, 0], radius: [1.5, 1.5], bugs: 0 },
  light: { holes: [4, 8], radius: [1.5, 2.1], bugs: 0 },
  moderate: { holes: [14, 22], radius: [2.0, 3.0], bugs: 1 },
  severe: { holes: [28, 44], radius: [3.0, 4.5], bugs: 3 },
};
// 해충 증거 색 — '거의 검정(중성)' 통일. 등급 증상(밝은 갈색·주황 괴사/화상)과
// 색 공간을 완전히 분리 (grade_paint.py PEST_HOLE_COLORS 와 동일 값).
const PEST_HOLE_COLORS = [[16, 13, 11], [10, 9, 8]];
const PEST_BUG_COLOR = [12, 10, 8];
const LEAF_COUNT = { column: 1, needle: 14, leaflet: 7, round: 6, serrated: 5, long: 6, lobe: 4 };

const SKY_PALETTES = [
  [138, 176, 214], [210, 200, 180], [90, 100, 110], [40, 48, 58], [176, 196, 148],
];
const EXTRA_SOILS = [
  [62, 48, 36], [120, 96, 70], [40, 36, 32], [150, 140, 128],
];

/** 결정론적 시드 RNG (mulberry32) — numpy 와 같은 분포를 다른 난수열로 재현. */
export function makeRng(seed) {
  let a = (seed >>> 0) || 1;
  const next = () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const rng = {
    uniform: (lo, hi) => lo + (hi - lo) * next(),
    int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)), // hi 포함
    chance: (p) => next() < p,
    pick: (arr) => arr[Math.floor(next() * arr.length)],
    normal: (mu, sigma) => {
      const u = Math.max(next(), 1e-9);
      const v = next();
      return mu + sigma * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    },
  };
  return rng;
}

const clip255 = (v) => (v < 0 ? 0 : v > 255 ? 255 : v);

/**
 * 합성 식물 장면 렌더 — grade_paint.paint 의 JS 포팅.
 * @returns {{ rgb: Uint8Array, width: number, height: number }}
 */
export function renderScene({ species = 'basil', width, height, grade, pest = 'none', seed = 1 }) {
  const spec = SPECIES[species] ?? SPECIES.basil;
  const st = GRADE_STYLE[grade] ?? GRADE_STYLE.healthy;
  const ps = PEST_STYLE[pest] ?? PEST_STYLE.none;
  const size = width; // 정방형 기하 — 폭 기준 스케일
  const s = size / 48;
  const rng = makeRng(seed);

  const img = new Float32Array(width * height * 3);
  // 배경 — 하늘/벽 + 흙 (화분 무관성)
  const sky = SKY_PALETTES[rng.int(0, SKY_PALETTES.length - 1)].map((c) => clip255(c + rng.normal(0, 10)));
  const soils = [spec.soil, ...EXTRA_SOILS];
  const soil = soils[rng.int(0, soils.length - 1)].map((c) => clip255(c + rng.normal(0, 8)));
  const horizon = Math.floor(height * rng.uniform(0.58, 0.82));
  for (let y = 0; y < height; y++) {
    const isSky = y < horizon;
    const base = isSky ? sky : soil;
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 3;
      img[i] = base[0]; img[i + 1] = base[1]; img[i + 2] = base[2];
      if (!isSky) {
        img[i] += rng.normal(0, 6); img[i + 1] += rng.normal(0, 6); img[i + 2] += rng.normal(0, 6);
      }
    }
  }

  const leaf = spec.leaf;
  const vein = spec.vein;
  const fruit = spec.fruit;
  const shape = spec.shape;
  const tint = st.tint;

  const nLeaves = shape === 'column'
    ? 1
    : Math.max(1, Math.round(LEAF_COUNT[shape] * st.leafMul * rng.uniform(0.90, 1.10)));

  const leafMask = new Uint8Array(width * height);
  const leafPixels = [];

  const paintDisk = (cy, cx, ry, rx, color) => {
    const ryy = Math.max(ry, 1);
    const rxx = Math.max(rx, 1);
    const y0 = Math.max(0, Math.ceil(cy - ryy));
    const y1 = Math.min(height - 1, Math.floor(cy + ryy));
    const x0 = Math.max(0, Math.ceil(cx - rxx));
    const x1 = Math.min(width - 1, Math.floor(cx + rxx));
    for (let y = y0; y <= y1; y++) {
      const dy = (y - cy) / ryy;
      for (let x = x0; x <= x1; x++) {
        const dx = (x - cx) / rxx;
        if (dy * dy + dx * dx <= 1) {
          const i = (y * width + x) * 3;
          img[i] = color[0]; img[i + 1] = color[1]; img[i + 2] = color[2];
          if (!leafMask[y * width + x]) {
            leafMask[y * width + x] = 1;
            leafPixels.push(y * width + x);
          }
        }
      }
    }
  };

  for (let li = 0; li < nLeaves; li++) {
    let cx, cy, ry, rx;
    if (shape === 'column') {
      // 선인장 계열 — 잎 수 대신 기둥 굵기로 활력 표현
      const colMul = 0.55 + 0.45 * Math.min(st.leafMul, 1.7) / 1.7;
      cx = width * 0.5;
      cy = height * 0.42 + st.wilt * 0.3 * s;
      ry = height * 0.32 * colMul * st.leafScale;
      rx = width * 0.14 * colMul;
    } else if (shape === 'needle') {
      cx = rng.uniform(0.2, 0.8) * width;
      cy = rng.uniform(0.25, 0.6) * height + st.wilt * s;
      ry = rng.uniform(6, 11) * st.leafScale * s;
      rx = rng.uniform(1.6, 2.8) * s;
    } else {
      cx = rng.uniform(0.22, 0.78) * width;
      cy = rng.uniform(0.18, 0.58) * height + st.wilt * s;
      const base = shape === 'leaflet' ? 7 : 9;
      ry = rng.uniform(base, base + 6) * st.leafScale * s;
      rx = rng.uniform(base - 2, base + 5) * s;
      if (shape === 'long') { ry = rng.uniform(10, 16) * st.leafScale * s; rx = rng.uniform(3, 5) * s; }
      if (shape === 'lobe') { ry = rng.uniform(10, 16) * st.leafScale * s; rx = rng.uniform(10, 16) * s; }
    }
    const t = [clip255(leaf[0] * tint[0] + rng.normal(0, 7)), clip255(leaf[1] * tint[1] + rng.normal(0, 7)), clip255(leaf[2] * tint[2] + rng.normal(0, 7))];
    paintDisk(cy, cx, ry, rx, t);
    // 잎맥
    const rr0 = cy - ry * 0.6;
    const rr1 = cy + ry * 0.6;
    for (let k = 0; k < 8; k++) {
      const y = Math.round(rr0 + (rr1 - rr0) * (k / 7));
      const x = Math.round(cx);
      if (y >= 1 && y < height - 1 && x >= 1 && x < width - 1) {
        for (let dy = -1; dy <= 1; dy++) {
          const i = ((y + dy) * width + x) * 3;
          img[i] = vein[0]; img[i + 1] = vein[1]; img[i + 2] = vein[2];
        }
      }
    }
  }

  // 열매 — 활력 있는 등급에서만
  if (['leaflet', 'serrated', 'long'].includes(shape) && ['very_healthy', 'healthy', 'fair'].includes(grade) && rng.chance(0.45)) {
    const n = rng.int(1, 3);
    for (let k = 0; k < n; k++) {
      const fy = rng.uniform(0.35, 0.62) * height;
      const fx = rng.uniform(0.3, 0.7) * width;
      const fr = rng.uniform(2.2, 4.2) * s;
      paintDisk(fy, fx, fr, fr, fruit);
    }
  }

  // 가장자리 화상 — 잎 외곽 비율만큼 '밝은 주황갈색'화 (해충 증거색과 분리)
  if (st.burn > 0 && leafPixels.length > 0) {
    let cySum = 0, cxSum = 0;
    for (const p of leafPixels) { cySum += Math.floor(p / width); cxSum += p % width; }
    const cyM = cySum / leafPixels.length;
    const cxM = cxSum / leafPixels.length;
    const dist = leafPixels.map((p) => {
      const y = Math.floor(p / width); const x = p % width;
      return Math.hypot(y - cyM, x - cxM);
    });
    const sorted = [...dist].sort((a, b) => a - b);
    const q = Math.max(0, 1 - st.burn);
    const thr = sorted[Math.min(sorted.length - 1, Math.floor(q * (sorted.length - 1)))];
    for (let i = 0; i < leafPixels.length; i++) {
      if (dist[i] > thr) {
        const p = leafPixels[i] * 3;
        img[p] = clip255(img[p] * 1.40 + 60);
        img[p + 1] = clip255(img[p + 1] * 0.50 + 15);
        img[p + 2] = clip255(img[p + 2] * 0.15);
      }
    }
  }

  // 괴사 반점 — 잎 픽셀 일부를 '밝은 황갈색' 반점화 (Python 과 동일 — 해충 구멍과 색 공간 분리)
  if (st.necrosis > 0 && leafPixels.length > 0) {
    const k = Math.floor(leafPixels.length * st.necrosis);
    for (let i = 0; i < k; i++) {
      const p = leafPixels[rng.int(0, leafPixels.length - 1)] * 3;
      img[p] = clip255(img[p] * 1.20 + 55);
      img[p + 1] = clip255(img[p + 1] * 0.90 + 30);
      img[p + 2] = clip255(img[p + 2] * 0.35);
    }
  }

  // 해충 — 식해 구멍 + 검은 벌레 덩어리 (등급과 독립 적용)
  if (leafPixels.length > 8) {
    const [lo, hi] = ps.holes;
    if (hi > 0) {
      const n = Math.min(rng.int(lo, hi), leafPixels.length);
      const [rlo, rhi] = ps.radius;
      for (let i = 0; i < n; i++) {
        const p = leafPixels[rng.int(0, leafPixels.length - 1)];
        const py = Math.floor(p / width);
        const px = p % width;
        const r = rng.uniform(rlo, rhi) * s;
        // 구멍은 잎 위에만 — 잎 마스크 제한 타원 (증거색: 거의 검정)
        const ryy = Math.max(r, 1);
        const y0 = Math.max(0, Math.ceil(py - ryy));
        const y1 = Math.min(height - 1, Math.floor(py + ryy));
        const x0 = Math.max(0, Math.ceil(px - ryy));
        const x1 = Math.min(width - 1, Math.floor(px + ryy));
        const col = PEST_HOLE_COLORS[rng.int(0, PEST_HOLE_COLORS.length - 1)];
        for (let y = y0; y <= y1; y++) {
          for (let x = x0; x <= x1; x++) {
            const dy = (y - py) / ryy; const dx = (x - px) / ryy;
            if (dy * dy + dx * dx <= 1 && leafMask[y * width + x]) {
              const j = (y * width + x) * 3;
              img[j] = col[0]; img[j + 1] = col[1]; img[j + 2] = col[2];
            }
          }
        }
      }
    }
    for (let b = 0; b < ps.bugs; b++) {
      const rows = [...new Set(leafPixels.map((p) => Math.floor(p / width)))];
      if (!rows.length) break;
      const by = rows[rng.int(0, rows.length - 1)];
      const cols = [];
      for (let x = 0; x < width; x++) if (leafMask[by * width + x]) cols.push(x);
      if (!cols.length) continue;
      const bx = cols[rng.int(0, cols.length - 1)];
      const br = rng.uniform(2.5, 4.5) * s;
      const ryy = Math.max(br, 1); const rxx = Math.max(br * 1.4, 1);
      const y0 = Math.max(0, Math.ceil(by - ryy));
      const y1 = Math.min(height - 1, Math.floor(by + ryy));
      const x0 = Math.max(0, Math.ceil(bx - rxx));
      const x1 = Math.min(width - 1, Math.floor(bx + rxx));
      for (let y = y0; y <= y1; y++) {
        for (let x = x0; x <= x1; x++) {
          const dy = (y - by) / ryy; const dx = (x - bx) / rxx;
          if (dy * dy + dx * dx <= 1 && leafMask[y * width + x]) {
            const j = (y * width + x) * 3;
            img[j] = PEST_BUG_COLOR[0]; img[j + 1] = PEST_BUG_COLOR[1]; img[j + 2] = PEST_BUG_COLOR[2];
          }
        }
      }
    }
  }

  // 센서 노이즈 + 8bit 양자화
  const rgb = new Uint8Array(width * height * 3);
  for (let i = 0; i < img.length; i++) {
    rgb[i] = Math.max(0, Math.min(255, Math.round(img[i] + rng.normal(0, 4.5))));
  }
  return { rgb, width, height };
}

/**
 * ROI 목록을 하나의 프레임(upright)으로 합성 — 각 식물 장면을 ROI 사각형에 렌더.
 * plants: [{ species, grade, pest, roi: { x, y, w, h } }] (roi 정규화 좌표)
 */
export function composeFrame(plants, { width, height, seed }) {
  const rgb = new Uint8Array(width * height * 3);
  plants.forEach((p, idx) => {
    const rx = Math.max(0, Math.round(p.roi.x * width));
    const ry = Math.max(0, Math.round(p.roi.y * height));
    const rw = Math.max(4, Math.round(p.roi.w * width));
    const rh = Math.max(4, Math.round(p.roi.h * height));
    const scene = renderScene({
      species: p.species, width: rw, height: rh,
      grade: p.grade, pest: p.pest, seed: seed + idx * 131,
    });
    for (let y = 0; y < rh && ry + y < height; y++) {
      for (let x = 0; x < rw && rx + x < width; x++) {
        const si = (y * rw + x) * 3;
        const di = ((ry + y) * width + (rx + x)) * 3;
        rgb[di] = scene.rgb[si];
        rgb[di + 1] = scene.rgb[si + 1];
        rgb[di + 2] = scene.rgb[si + 2];
      }
    }
  });
  return { rgb, width, height };
}

/**
 * 프레임의 임의 사각 영역을 48x48 RGB 0..1 로 박스 평균 다운스케일.
 * rect: 픽셀 좌표 { x, y, w, h }
 * @returns {Float32Array} (48*48*3)
 */
export function cropSample48(rgb, W, H, rect) {
  const out = new Float32Array(48 * 48 * 3);
  const rx0 = Math.max(0, Math.min(W - 1, rect.x));
  const ry0 = Math.max(0, Math.min(H - 1, rect.y));
  const rw = Math.max(1, Math.min(W - rx0, rect.w));
  const rh = Math.max(1, Math.min(H - ry0, rect.h));
  for (let j = 0; j < 48; j++) {
    const sy0 = Math.floor(ry0 + (j * rh) / 48);
    const sy1 = Math.max(sy0 + 1, Math.floor(ry0 + ((j + 1) * rh) / 48));
    for (let i = 0; i < 48; i++) {
      const sx0 = Math.floor(rx0 + (i * rw) / 48);
      const sx1 = Math.max(sx0 + 1, Math.floor(rx0 + ((i + 1) * rw) / 48));
      let r = 0, g = 0, b = 0, n = 0;
      for (let y = sy0; y < Math.min(ry0 + rh, sy1); y++) {
        for (let x = sx0; x < Math.min(rx0 + rw, sx1); x++) {
          const p = (y * W + x) * 3;
          r += rgb[p]; g += rgb[p + 1]; b += rgb[p + 2]; n++;
        }
      }
      const o = (j * 48 + i) * 3;
      out[o] = r / (n * 255);
      out[o + 1] = g / (n * 255);
      out[o + 2] = b / (n * 255);
    }
  }
  return out;
}

/** 녹색 픽셀 비율(0..1) — 이벤트 payload 표시용 보조 지표. */
export function greenness(rgb48) {
  let n = 0;
  for (let i = 0; i < 48 * 48; i++) {
    const r = rgb48[i * 3], g = rgb48[i * 3 + 1], b = rgb48[i * 3 + 2];
    if (g > r + 0.03 && g > b + 0.03) n++;
  }
  return n / (48 * 48);
}
