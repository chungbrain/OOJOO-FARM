// F4/F5 — 가상 카메라 시뮬레이터.
// 실제 CameraX 체인을 충실하게 재현한다:
//   센서 버퍼(가로 640x480, sensorOrientation 90 고정 — 후면 카메라 전형)
//   → mount 정책 기반 targetRotation 결정 → EXIF 회전 기록
//   → master 는 EXIF 적용 upright 사진을 수신 (가로/세로 자동 결정 검증)
import { composeFrame } from '../vision/plantScene.js';
import { encodePng } from '../vision/png.js';
import { resolveCaptureOrientation, rotateCw } from './orientation.js';

export class CameraSim {
  /**
   * @param {{ sensorOrientation?: number, sensorWidth?: number, sensorHeight?: number }} opts
   * sensorOrientation 90 — 스마트폰 후면 카메라 전형 (natural portrait 센서, 가로 버퍼)
   */
  constructor({ sensorOrientation = 90, sensorWidth = 640, sensorHeight = 480 } = {}) {
    this.sensorOrientation = sensorOrientation;
    this.sensorWidth = sensorWidth;
    this.sensorHeight = sensorHeight;
  }

  /**
   * 사진 촬영 — mount(카메라 설치 위치) 정책에 따라 가로/세로 모드를 자동 선택.
   * @param {{ plants: Array, mount: string, displayRotation?: number, cycle: number }} world
   *   plants — composeFrame 재질 [{ species, grade, pest, roi }] (roi 정규화)
   * @returns {{ captureMode, targetRotation, exifRotation, image, png, takenAt, width, height }}
   */
  capture({ plants, mount = 'auto', displayRotation = 0, cycle = 0 }) {
    const plan = resolveCaptureOrientation({
      sensorOrientation: this.sensorOrientation,
      displayRotation,
      mount,
    });
    // upright 프레임 합성 → 센서 버퍼(역회전) → EXIF 재적용 upright 최종본.
    // 시뮬레이션은 합성/역회전/재적용의 전체 체인을 그대로 수행한다.
    const upright = composeFrame(plants, {
      width: plan.captureMode === 'landscape' ? this.sensorWidth : this.sensorHeight,
      height: plan.captureMode === 'landscape' ? this.sensorHeight : this.sensorWidth,
      seed: 5000 + cycle * 17,
    });
    const sensorBuffer = rotateCw(upright, (360 - plan.exifRotation) % 360); // EXIF 해제 → 센서 방향
    const display = rotateCw(sensorBuffer, plan.exifRotation); // EXIF 적용 → master 가 보는 사진
    const png = encodePng(display.rgb, display.width, display.height);
    return {
      ...plan,
      image: display,
      png,
      width: display.width,
      height: display.height,
      takenAt: new Date().toISOString(),
    };
  }
}
