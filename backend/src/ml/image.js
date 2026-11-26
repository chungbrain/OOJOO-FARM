// 학습 샘플 이미지 처리 — JPEG 디코드(EXIF 회전 반영) / 영역 자르기 / 면적 평균 축소 / JPEG 인코드.
// 이미지는 { width, height, data: Uint8Array(RGB, 행 우선) } 로 다룬다.
import jpeg from 'jpeg-js';

const MAX_MP = 40; // 디코드 허용 최대 화소 (메가픽셀)

export function isJpeg(buf) {
  return buf && buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
}

/** EXIF Orientation(0x0112) — 없으면 1. 휴대폰 사진은 픽셀은 그대로 두고 이 태그로 회전을 표시한다. */
export function exifOrientation(buf) {
  let off = 2;
  while (off + 4 < buf.length) {
    if (buf[off] !== 0xff) return 1;
    const marker = buf[off + 1];
    const len = buf.readUInt16BE(off + 2);
    if (marker === 0xe1 && buf.toString('ascii', off + 4, off + 8) === 'Exif') {
      const t = off + 10; // TIFF 헤더
      const le = buf.toString('ascii', t, t + 2) === 'II';
      const u16 = (p) => (le ? buf.readUInt16LE(p) : buf.readUInt16BE(p));
      const u32 = (p) => (le ? buf.readUInt32LE(p) : buf.readUInt32BE(p));
      const ifd = t + u32(t + 4);
      if (ifd + 2 > buf.length) return 1;
      const n = u16(ifd);
      for (let i = 0; i < n; i++) {
        const e = ifd + 2 + i * 12;
        if (e + 12 > buf.length) break;
        if (u16(e) === 0x0112) {
          const v = u16(e + 8);
          return v >= 1 && v <= 8 ? v : 1;
        }
      }
      return 1;
    }
    if (marker === 0xda) return 1; // 스캔 시작 — 더 이상 메타데이터 없음
    off += 2 + len;
  }
  return 1;
}

/** EXIF orientation 1..8 을 픽셀에 반영해 똑바로 선 이미지로. */
export function applyOrientation(img, o) {
  if (o === 1) return img;
  const { width: w, height: h, data } = img;
  const swap = o >= 5;
  const W = swap ? h : w;
  const H = swap ? w : h;
  const out = new Uint8Array(W * H * 3);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let sx; let sy;
      switch (o) {
        case 2: sx = w - 1 - x; sy = y; break;
        case 3: sx = w - 1 - x; sy = h - 1 - y; break;
        case 4: sx = x; sy = h - 1 - y; break;
        case 5: sx = y; sy = x; break;
        case 6: sx = y; sy = h - 1 - x; break;
        case 7: sx = w - 1 - y; sy = h - 1 - x; break;
        case 8: sx = w - 1 - y; sy = x; break;
        default: sx = x; sy = y;
      }
      const si = (sy * w + sx) * 3;
      const di = (y * W + x) * 3;
      out[di] = data[si]; out[di + 1] = data[si + 1]; out[di + 2] = data[si + 2];
    }
  }
  return { width: W, height: H, data: out };
}

export function decodeImage(buf) {
  if (!isJpeg(buf)) throw new Error('JPEG 이미지만 지원합니다');
  const raw = jpeg.decode(buf, { useTArray: true, formatAsRGBA: false, maxResolutionInMP: MAX_MP, maxMemoryUsageInMB: 1024 });
  const img = { width: raw.width, height: raw.height, data: raw.data };
  return applyOrientation(img, exifOrientation(buf));
}

export function encodeJpeg(img, quality = 90) {
  const { width, height, data } = img;
  const rgba = Buffer.alloc(width * height * 4);
  for (let i = 0, j = 0; i < width * height; i++, j += 3) {
    rgba[i * 4] = data[j]; rgba[i * 4 + 1] = data[j + 1]; rgba[i * 4 + 2] = data[j + 2]; rgba[i * 4 + 3] = 255;
  }
  return Buffer.from(jpeg.encode({ data: rgba, width, height }, quality).data);
}

/** 정규화 영역 {x,y,w,h}(0..1) 자르기 — farmer 의 RoI 크롭과 같은 반올림 규칙. */
export function cropNormalized(img, roi) {
  const bw = img.width;
  const bh = img.height;
  const left = Math.min(Math.max(Math.floor(roi.x * bw), 0), bw - 2);
  const top = Math.min(Math.max(Math.floor(roi.y * bh), 0), bh - 2);
  const width = Math.min(Math.max(Math.floor(roi.w * bw), 2), bw - left);
  const height = Math.min(Math.max(Math.floor(roi.h * bh), 2), bh - top);
  return cropPixels(img, left, top, width, height);
}

export function cropPixels(img, left, top, width, height) {
  const out = new Uint8Array(width * height * 3);
  for (let y = 0; y < height; y++) {
    const s = ((top + y) * img.width + left) * 3;
    out.set(img.data.subarray(s, s + width * 3), y * width * 3);
  }
  return { width, height, data: out };
}

/**
 * 영역(sx,sy,sw,sh 픽셀, 실수 가능)을 tw×th 로 면적 평균 축소 → Float32Array 0..1 (H,W,C).
 * 종횡비는 맞추지 않는다 — farmer 가 RoI 를 48×48 로 늘려 넣는 것과 같게.
 */
export function resampleToFloat(img, tw, th, sx = 0, sy = 0, sw = img.width, sh = img.height) {
  const out = new Float32Array(tw * th * 3);
  const { width: W, data } = img;
  for (let ty = 0; ty < th; ty++) {
    const y0 = sy + (ty * sh) / th;
    const y1 = sy + ((ty + 1) * sh) / th;
    const ya = Math.floor(y0);
    const yb = Math.max(ya + 1, Math.ceil(y1));
    for (let tx = 0; tx < tw; tx++) {
      const x0 = sx + (tx * sw) / tw;
      const x1 = sx + ((tx + 1) * sw) / tw;
      const xa = Math.floor(x0);
      const xb = Math.max(xa + 1, Math.ceil(x1));
      let r = 0; let g = 0; let b = 0; let n = 0;
      for (let y = ya; y < yb && y < img.height; y++) {
        for (let x = xa; x < xb && x < W; x++) {
          const i = (y * W + x) * 3;
          r += data[i]; g += data[i + 1]; b += data[i + 2]; n++;
        }
      }
      const o = (ty * tw + tx) * 3;
      out[o] = r / n / 255; out[o + 1] = g / n / 255; out[o + 2] = b / n / 255;
    }
  }
  return out;
}

/** 긴 변이 maxSide 이하가 되게 축소 (작으면 그대로). */
export function fitWithin(img, maxSide) {
  const s = Math.max(img.width, img.height);
  if (s <= maxSide) return img;
  const tw = Math.max(2, Math.round((img.width * maxSide) / s));
  const th = Math.max(2, Math.round((img.height * maxSide) / s));
  const f = resampleToFloat(img, tw, th);
  const data = new Uint8Array(f.length);
  for (let i = 0; i < f.length; i++) data[i] = Math.round(f[i] * 255);
  return { width: tw, height: th, data };
}
