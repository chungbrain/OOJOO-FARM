// F4 — 카메라 위치 기반 자동 가로/세로 촬영 방향 리졸버.
// Android(vision/CameraHost.kt + 정책 camera_mount)와 PC 시뮬레이션이
// 같은 규격(docs/farmer-features.md §4)으로 동작한다.
//
// 입력:
//   sensorOrientation — 센서 고정 각(후면 카메라 통상 90°, natural portrait)
//   displayRotation   — 0..3 (Surface.ROTATION_*; auto 판단 시 사용)
//   mount             — 'auto' | 'landscape' | 'portrait' (카메라 물리 설치 위치)
//
// 출력:
//   targetRotation — CameraX use case 에 지정할 회전(0..3)
//   exifRotation   — 사진 파일에 기록되는 시계방향 회전(도) = upright 변환각
//   captureMode    — 최종 upright 사진의 방향('landscape' | 'portrait')

const ROTATION_DEG = [0, 90, 180, 270];

/** natural portrait 센서(90/270)인지 — 스마트폰 후면 카메라의 전형. */
export function isNaturalPortraitSensor(sensorOrientation) {
  return sensorOrientation === 90 || sensorOrientation === 270;
}

/** F4 규격 리졸버. */
export function resolveCaptureOrientation({ sensorOrientation = 90, displayRotation = 0, mount = 'auto' }) {
  const naturalPortrait = isNaturalPortraitSensor(sensorOrientation);
  let targetRotation;
  if (mount === 'landscape') {
    // 가로 설치 고정 — natural portrait 센서는 ROTATION_90(1), natural landscape 센서는 0
    targetRotation = naturalPortrait ? 1 : 0;
  } else if (mount === 'portrait') {
    // 세로 설치 고정 — natural portrait 센서는 ROTATION_0(0), natural landscape 센서는 1
    targetRotation = naturalPortrait ? 0 : 1;
  } else {
    // auto — 디스플레이 회전 감지(기존 CameraHost DisplayListener 로직과 동일 기준)
    targetRotation = Math.max(0, Math.min(3, Math.round(displayRotation)));
  }
  const targetDeg = ROTATION_DEG[targetRotation] ?? 0;
  const exifRotation = (((sensorOrientation - targetDeg) % 360) + 360) % 360;
  // upright 출력 방향 — natural 방향과 target 회전의 XOR 로 판정
  const rotatedQuarter = targetRotation % 2 === 1;
  const outputPortrait = naturalPortrait !== rotatedQuarter;
  return {
    targetRotation,
    exifRotation,
    captureMode: outputPortrait ? 'portrait' : 'landscape',
  };
}

/**
 * 이미지를 시계방향으로 회전 (EXIF 적용 = master 가 보는 upright 최종본).
 * @param {{ rgb: Uint8Array, width: number, height: number }} img
 */
export function rotateCw(img, degrees) {
  const d = (((Math.round(degrees / 90) * 90) % 360) + 360) % 360;
  if (d === 0) return img;
  const { rgb, width: W, height: H } = img;
  if (d === 90) {
    const out = new Uint8Array(W * H * 3);
    const w2 = H; // 출력 폭 = 입력 높이
    for (let sy = 0; sy < H; sy++) {
      for (let sx = 0; sx < W; sx++) {
        const dx = H - 1 - sy;
        const dy = sx;
        const si = (sy * W + sx) * 3;
        const di = (dy * w2 + dx) * 3;
        out[di] = rgb[si]; out[di + 1] = rgb[si + 1]; out[di + 2] = rgb[si + 2];
      }
    }
    return { rgb: out, width: w2, height: W };
  }
  if (d === 180) {
    const out = new Uint8Array(W * H * 3);
    for (let sy = 0; sy < H; sy++) {
      for (let sx = 0; sx < W; sx++) {
        const dx = W - 1 - sx;
        const dy = H - 1 - sy;
        const si = (sy * W + sx) * 3;
        const di = (dy * W + dx) * 3;
        out[di] = rgb[si]; out[di + 1] = rgb[si + 1]; out[di + 2] = rgb[si + 2];
      }
    }
    return { rgb: out, width: W, height: H };
  }
  // 270
  const out = new Uint8Array(W * H * 3);
  const w2 = H;
  for (let sy = 0; sy < H; sy++) {
    for (let sx = 0; sx < W; sx++) {
      const dx = sy;
      const dy = W - 1 - sx;
      const si = (sy * W + sx) * 3;
      const di = (dy * w2 + dx) * 3;
      out[di] = rgb[si]; out[di + 1] = rgb[si + 1]; out[di + 2] = rgb[si + 2];
    }
  }
  return { rgb: out, width: w2, height: W };
}
