// F5 — Farmer 자율 엔진 시뮬레이터 (android/slave service/FarmerEngine.kt 미러).
//
// 자율 루프:
//   heartbeat → 정책 동기화(camera_mount/roi_interval) → ROI 모니터링
//   ROI 모니터링: upright 프레임 촬영 → ROI crop → JevHealthNet(건강등급 5단계)
//                + PestNet(해충 4단계, 별도 모델) → 분석 리포트 업로드
//                → 등급/해충 전이 감지 시 health_change 이벤트 (master 실시간 알림)
// 명령 (SSE, 폴링 fallback): capture_photo — camera_mount 정책 기반 가로/세로 자동 촬영.
import { openSse, sleep } from '../net/api.js';
import * as jevHealth from '../models/jevHealth.js';
import * as pestNet from '../models/pestNet.js';
import { composeFrame, cropSample48, greenness } from '../vision/plantScene.js';
import { resolveCaptureOrientation, rotateCw } from './orientation.js';

export class FarmerSim {
  /**
   * @param {{ api: import('../net/api.js').Api, camera: import('./cameraSim.js').CameraSim,
   *           world: object, cycleMs?: number, logger?: (msg: string) => void }} opts
   * world — 가상 재배 환경 (시나리오가 식물 상태를 변화시킨다):
   *   { plants: [{ plantId, name, species, roi, grade, pest }],
   *     displayRotation: 0..3, battery: 100 }
   */
  constructor({ api, camera, world, cycleMs = 350, confirmCycles = 2, logger = () => {} }) {
    this.api = api;
    this.camera = camera;
    this.world = world;
    this.cycleMs = cycleMs;
    // F3 전이 확정 임계값 — 새 상태가 연속 N회 관측될 때만 전이 선언.
    // 단일 사이클 오분류(플리커)가 master 알림을 유발하지 않게 한다.
    // (실기기 FarmerEngine 의 이벤트 디바운스와 같은 목적 — PC 시뮬레이션에서 발견해 규격에 반영)
    this.confirmCycles = Math.max(1, confirmCycles);
    this.logger = logger;
    this.running = false;
    this.paused = false;
    this.policy = { camera_mount: 'auto', roi_interval: 20 };
    this.lastByPlant = new Map(); // plantId → { grade, pestLevel, pending, pendingCount }
    this.eventQueue = [];         // 오프라인 큐 (FarmerEngine flushQueue 미러)
    this.cycleCount = 0;
    this.eventLog = [];           // 전송 성공한 이벤트 로그 (검증용)
    this.commandLog = [];
  }

  /** 페어링 — 세션키 발급. (slave PairingScreen 와 동일 플로우) */
  async pair(baseUrl, code, name = 'PC-Sim-Farmer') {
    const { Api } = await import('../net/api.js');
    const creds = await Api.verify(baseUrl, code, name);
    this.api = new Api(baseUrl, { slaveId: creds.slaveId, sessionKey: creds.sessionKey });
    return creds;
  }

  /** 서버 식물 목록 → world plant 에 plantId 연결 (이름 매칭). */
  async fetchPlants() {
    const { plants } = await this.api.slavePlants();
    for (const p of plants) {
      const w = this.world.plants.find((x) => x.name === p.name);
      if (w) w.plantId = p.id;
    }
    return plants;
  }

  async start() {
    this.running = true;
    // SSE 명령 구독 (FarmerEngine.startSSE 미러)
    this.sse = openSse(this.api.baseUrl, `/api/commands/sse/slave/${this.api.slaveId}`, {
      headers: this.api.authHeaders(),
      onEvent: (ev) => {
        if (ev?.type === 'command') this.handleCommand(ev.command).catch((e) => this.logger(`명령 오류: ${e.message}`));
      },
    });
    this.loopPromise = this.runLoop();
  }

  async stop() {
    this.running = false;
    try { this.sse?.close(); } catch {}
    await sleep(50);
  }

  async runLoop() {
    while (this.running) {
      try {
        await this.api.heartbeat(this.world.battery ?? 100);
        await this.flushQueue(); // 재연결 시 오프라인 이벤트 동기화
        await this.syncPolicy();
        if (!this.paused) await this.runRoiMonitoring();
        this.cycleCount++;
      } catch (e) {
        this.logger(`루프 오류(오프라인 큐 적재): ${e.message}`);
      }
      await sleep(this.cycleMs);
    }
  }

  /** 정책 동기화 — camera_mount(가로/세로 촬영) 포함. */
  async syncPolicy() {
    try {
      const p = await this.api.policy();
      this.policy = p;
    } catch { /* 서버 다운 시 기존 정책 유지 */ }
  }

  /** ROI 모니터링 사이클 — FarmerEngine.runRoiMonitoring 미러. */
  async runRoiMonitoring() {
    if (!this.world.plants.length) return;
    const frame = this.captureFrameUpright();
    for (const p of this.world.plants) {
      if (!p.plantId || !p.roi) continue;
      const rect = {
        x: Math.round(p.roi.x * frame.width),
        y: Math.round(p.roi.y * frame.height),
        w: Math.round(p.roi.w * frame.width),
        h: Math.round(p.roi.h * frame.height),
      };
      const rgb48 = cropSample48(frame.rgb, frame.width, frame.height, rect);
      // F1 — Jev 건강등급 (초경량, 즉시 판단)
      const grade = jevHealth.infer(rgb48);
      // F2 — 해충발생 (건강진단과 별도 모델)
      const pest = pestNet.infer(rgb48);
      const green = greenness(rgb48);
      const status = {
        plantId: p.plantId,
        plantName: p.name,
        healthGrade: grade.grade,
        healthGradeKo: grade.gradeKo,
        confidence: Number(grade.confidence.toFixed(4)),
        pestLevel: pest.level,
        pestLevelKo: pest.levelKo,
        pestConfidence: Number(pest.confidence.toFixed(4)),
        greenness: Number(green.toFixed(4)),
        modelId: grade.modelId,
        pestModelId: pest.modelId,
        capturedAt: new Date().toISOString(),
      };
      try {
        await this.api.reportAnalysis(p.plantId, status);
      } catch (e) {
        this.logger(`분석 리포트 실패(${p.name}): ${e.message}`);
      }
      // F3 — 건강상태 전이 감지 (연속 confirmCycles 회 관측으로 확정) → master 알림.
      // 단일 사이클 오분류(플리커)는 pending 으로만 기록되고 알림을 유발하지 않는다.
      const cur = { grade: grade.grade, pestLevel: pest.level };
      let prev = this.lastByPlant.get(p.plantId);
      if (!prev) {
        this.lastByPlant.set(p.plantId, { grade: cur.grade, pestLevel: cur.pestLevel, pending: null, pendingCount: 0 });
        continue;
      }
      const same = prev.grade === cur.grade && prev.pestLevel === cur.pestLevel;
      if (same) {
        prev.pending = null;
        prev.pendingCount = 0;
        continue;
      }
      if (prev.pending && prev.pending.grade === cur.grade && prev.pending.pestLevel === cur.pestLevel) {
        prev.pendingCount += 1;
      } else {
        prev.pending = { grade: cur.grade, pestLevel: cur.pestLevel };
        prev.pendingCount = 1;
      }
      if (prev.pendingCount < this.confirmCycles) continue; // 아직 확정 안 됨
      // 전이 확정 → health_change 이벤트
      const payload = {
        from: prev.grade, to: cur.grade,
        fromKo: jevHealth.LABEL_KO[prev.grade] ?? prev.grade,
        toKo: grade.gradeKo,
        confidence: status.confidence,
        modelId: grade.modelId,
        greenness: status.greenness,
      };
      if (prev.pestLevel !== cur.pestLevel) {
        payload.pest = {
          from: prev.pestLevel, to: cur.pestLevel,
          level: pest.level, levelKo: pest.levelKo,
          confidence: status.pestConfidence,
          modelId: pest.modelId,
        };
      }
      await this.postEventSafe('health_change', payload, p.plantId);
      this.logger(`[전이] ${p.name}: ${payload.fromKo} → ${payload.toKo}` + (payload.pest ? ` (해충: ${payload.pest.levelKo})` : ''));
      this.lastByPlant.set(p.plantId, { grade: cur.grade, pestLevel: cur.pestLevel, pending: null, pendingCount: 0 });
    }
  }

  /**
   * ROI 모니터링용 upright 프레임 — CameraHolder.captureFrame 미러
   * (센서 버퍼 → rotationDegrees 적용 upright 비트맵; ROI 좌표계는 upright 기준).
   */
  captureFrameUpright() {
    const plan = resolveCaptureOrientation({
      sensorOrientation: this.camera.sensorOrientation,
      displayRotation: this.world.displayRotation ?? 0,
      mount: 'auto',
    });
    const uprightW = plan.captureMode === 'landscape' ? this.camera.sensorWidth : this.camera.sensorHeight;
    const uprightH = plan.captureMode === 'landscape' ? this.camera.sensorHeight : this.camera.sensorWidth;
    const upright = composeFrame(this.world.plants, { width: uprightW, height: uprightH, seed: 9000 + this.cycleCount * 13 });
    const sensorBuffer = rotateCw(upright, (360 - plan.exifRotation) % 360);
    return rotateCw(sensorBuffer, plan.exifRotation); // upright 최종본
  }

  /** 이벤트 전송 — 실패 시 오프라인 큐 적재 후 하트비트에서 flush. */
  async postEventSafe(type, payload, plantId = null) {
    try {
      await this.api.reportEvent(type, payload, plantId);
      this.eventLog.push({ type, payload, plantId, at: new Date().toISOString() });
    } catch {
      this.eventQueue.push({ type, payload, plantId });
    }
  }

  async flushQueue() {
    while (this.eventQueue.length) {
      const { type, payload, plantId } = this.eventQueue[0];
      try {
        await this.api.reportEvent(type, payload, plantId);
        this.eventLog.push({ type, payload, plantId, at: new Date().toISOString() });
        this.eventQueue.shift();
      } catch { break; }
    }
  }

  /** SSE 명령 처리 — FarmerEngine.handleSSECommand 미러. */
  async handleCommand(cmd) {
    this.commandLog.push(cmd);
    this.logger(`[명령] ${cmd.action} (id=${cmd.id})`);
    try {
      switch (cmd.action) {
        case 'capture_photo':
          await this.handleCapturePhoto(cmd);
          break;
        case 'water':
          await this.api.wateringLog({
            plantId: cmd.plant_id,
            amountMl: cmd.amount_ml ?? 300,
            source: 'manual',
            weatherFactor: cmd.weather_factor ?? 1,
          });
          await this.postEventSafe('manual_water', { amountMl: cmd.amount_ml ?? 300, source: 'manual' }, cmd.plant_id);
          break;
        case 'pause':
          this.paused = true;
          break;
        case 'resume':
          this.paused = false;
          break;
        default:
          // fan/laser/capture_video/generate_growth_clip/diagnose_video — Android 단계 구현
          this.logger(`[명령] ${cmd.action} — PC 시뮬 범위 외 (Android 이식 단계 구현)`);
          break;
      }
    } finally {
      try { await this.api.commandDone(cmd.id); } catch { /* 이미 done 처리 무시 */ }
    }
  }

  /** F4 — capture_photo: 카메라 설치 위치(camera_mount 정책) 기반 가로/세로 자동 촬영. */
  async handleCapturePhoto(cmd) {
    const mount = this.policy.camera_mount ?? 'auto';
    const shot = this.camera.capture({
      plants: this.world.plants,
      mount,
      displayRotation: this.world.displayRotation ?? 0,
      cycle: this.cycleCount,
    });
    const plant = this.world.plants.find((p) => p.plantId === cmd.plant_id) ?? this.world.plants[0];
    await this.api.uploadPhoto(shot.png, {
      plantId: plant?.plantId ?? null,
      takenAt: shot.takenAt,
      commandId: cmd.id,
    });
    this.logger(`[촬영] mount=${mount} → ${shot.captureMode} ${shot.width}x${shot.height} 업로드 완료 (exif=${shot.exifRotation}°)`);
    return shot;
  }
}
