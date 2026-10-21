// 경량 PNG 인코더/헤더 파서 — 외부 의존성 없이 Node 내장 zlib 만 사용.
// 시뮬레이션 사진은 PNG 로 업로드되고, master 가 받은 사진의 가로/세로를
// 헤더 파싱으로 검증한다 (S4 자동 방향 촬영 시나리오).

import zlib from 'node:zlib';

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

/**
 * RGB8 → PNG. 모든 스캔라인 필터 0(None) — 별도 디코더 없이 헤더만으로 검증 가능.
 * @param {Uint8Array|Buffer} rgb — (width*height*3) RGB
 */
export function encodePng(rgb, width, height) {
  if (rgb.length !== width * height * 3) throw new Error('rgb buffer size mismatch');
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // color type: RGB
  const stride = 1 + width * 3;
  const raw = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const si = (y * width + x) * 3;
      const di = y * stride + 1 + x * 3;
      raw[di] = rgb[si];
      raw[di + 1] = rgb[si + 1];
      raw[di + 2] = rgb[si + 2];
    }
  }
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

/** PNG 시그니처 + IHDR 크기 파싱 (픽셀 디코딩 불필요 — 방향 검증용). */
export function pngSize(buf) {
  if (buf.length < 24) throw new Error('too small for png');
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png');
  const w = buf.readUInt32BE(16);
  const h = buf.readUInt32BE(20);
  return { width: w, height: h, orientation: w > h ? 'landscape' : (h > w ? 'portrait' : 'square') };
}
