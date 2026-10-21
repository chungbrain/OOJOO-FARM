// F4 — 카메라 위치 기반 가로/세로 자동 촬영 방향 리졸버 단위 테스트.
// 규격: docs/farmer-features.md §4
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveCaptureOrientation, rotateCw, isNaturalPortraitSensor } from '../src/engine/orientation.js';
import { CameraSim } from '../src/engine/cameraSim.js';
import { renderScene } from '../src/vision/plantScene.js';
import { pngSize } from '../src/vision/png.js';

test('리졸버: natural portrait 센서(90°) — auto/landscape/portrait 행렬', () => {
  assert.ok(isNaturalPortraitSensor(90));
  assert.ok(isNaturalPortraitSensor(270));
  assert.ok(!isNaturalPortraitSensor(0));

  // auto + 디스플레이 0(세로 설치) → 세로모드, EXIF 90
  let r = resolveCaptureOrientation({ sensorOrientation: 90, displayRotation: 0, mount: 'auto' });
  assert.equal(r.targetRotation, 0);
  assert.equal(r.exifRotation, 90);
  assert.equal(r.captureMode, 'portrait');

  // auto + 디스플레이 1(가로 설치) → 가로모드, EXIF 0
  r = resolveCaptureOrientation({ sensorOrientation: 90, displayRotation: 1, mount: 'auto' });
  assert.equal(r.targetRotation, 1);
  assert.equal(r.exifRotation, 0);
  assert.equal(r.captureMode, 'landscape');

  // 가로 고정 설치 — 디스플레이와 무관하게 가로
  r = resolveCaptureOrientation({ sensorOrientation: 90, displayRotation: 0, mount: 'landscape' });
  assert.equal(r.targetRotation, 1);
  assert.equal(r.exifRotation, 0);
  assert.equal(r.captureMode, 'landscape');

  // 세로 고정 설치 — 디스플레이와 무관하게 세로
  r = resolveCaptureOrientation({ sensorOrientation: 90, displayRotation: 1, mount: 'portrait' });
  assert.equal(r.targetRotation, 0);
  assert.equal(r.exifRotation, 90);
  assert.equal(r.captureMode, 'portrait');
});

test('리졸버: natural landscape 센서(0°) — 반대 규칙 확인', () => {
  let r = resolveCaptureOrientation({ sensorOrientation: 0, displayRotation: 0, mount: 'landscape' });
  assert.equal(r.targetRotation, 0);
  assert.equal(r.exifRotation, 0);
  assert.equal(r.captureMode, 'landscape');

  r = resolveCaptureOrientation({ sensorOrientation: 0, displayRotation: 0, mount: 'portrait' });
  assert.equal(r.targetRotation, 1);
  assert.equal(r.exifRotation, 270);
  assert.equal(r.captureMode, 'portrait');

  r = resolveCaptureOrientation({ sensorOrientation: 0, displayRotation: 1, mount: 'auto' });
  assert.equal(r.captureMode, 'portrait');
});

test('rotateCw: 픽셀 정확 회전 (90° 시계방향 검증)', () => {
  // 2x2: a b / c d  →  90° CW: c a / d b
  const img = { rgb: Uint8Array.from([1, 0, 0, 2, 0, 0, 3, 0, 0, 4, 0, 0]), width: 2, height: 2 };
  const r90 = rotateCw(img, 90);
  assert.equal(r90.width, 2);
  assert.equal(r90.height, 2);
  assert.deepEqual(Array.from(r90.rgb), [3, 0, 0, 1, 0, 0, 4, 0, 0, 2, 0, 0]);
  // 360° 회전은 항등
  const r360 = rotateCw(rotateCw(rotateCw(rotateCw(img, 90), 90), 90), 90);
  assert.deepEqual(Array.from(r360.rgb), Array.from(img.rgb));
});

test('rotateCw: 640x480 → 90° = 480x640, 180° = 640x480, 270° = 480x640', () => {
  const img = { rgb: new Uint8Array(640 * 480 * 3), width: 640, height: 480 };
  assert.deepEqual([rotateCw(img, 90).width, rotateCw(img, 90).height], [480, 640]);
  assert.deepEqual([rotateCw(img, 180).width, rotateCw(img, 180).height], [640, 480]);
  assert.deepEqual([rotateCw(img, 270).width, rotateCw(img, 270).height], [480, 640]);
});

test('CameraSim: mount 정책에 따른 최종 사진 방향 (센서 90° 체인)', () => {
  const camera = new CameraSim();
  const plants = [{ species: 'basil', grade: 'healthy', pest: 'none', roi: { x: 0, y: 0, w: 1, h: 1 } }];

  const landscape = camera.capture({ plants, mount: 'landscape', displayRotation: 0, cycle: 1 });
  assert.equal(landscape.captureMode, 'landscape');
  assert.equal(landscape.width, 640);
  assert.equal(landscape.height, 480);
  assert.equal(pngSize(landscape.png).orientation, 'landscape');

  const portrait = camera.capture({ plants, mount: 'portrait', displayRotation: 1, cycle: 2 });
  assert.equal(portrait.captureMode, 'portrait');
  assert.equal(portrait.width, 480);
  assert.equal(portrait.height, 640);
  assert.equal(pngSize(portrait.png).orientation, 'portrait');

  const autoLandscape = camera.capture({ plants, mount: 'auto', displayRotation: 1, cycle: 3 });
  assert.equal(autoLandscape.captureMode, 'landscape');
  assert.equal(autoLandscape.width, 640);

  const autoPortrait = camera.capture({ plants, mount: 'auto', displayRotation: 0, cycle: 4 });
  assert.equal(autoPortrait.captureMode, 'portrait');
  assert.equal(autoPortrait.width, 480);
});

test('plantScene: 동일 시드 → 결정론적 재현 (다른 시드 → 다른 장면)', () => {
  const a = renderScene({ species: 'basil', width: 96, height: 96, grade: 'healthy', pest: 'none', seed: 42 });
  const b = renderScene({ species: 'basil', width: 96, height: 96, grade: 'healthy', pest: 'none', seed: 42 });
  const c = renderScene({ species: 'basil', width: 96, height: 96, grade: 'healthy', pest: 'none', seed: 43 });
  assert.deepEqual(a.rgb, b.rgb);
  assert.notDeepEqual(a.rgb, c.rgb);
});
