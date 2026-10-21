// F5 — Master 시뮬레이터 (android/master ui/LiveCameraScreen.kt + 알림 센터 미러).
// 페어링 코드 발급, 식물 등록, capture_photo 원격 지시 + photo_ready SSE 수신,
// 알림 센터(폴링)/health_change 실시간 push 수신을 검증한다.
import { openSse, sleep } from '../net/api.js';
import { pngSize } from '../vision/png.js';

export class MasterSim {
  constructor({ baseUrl, userId, logger = () => {} }) {
    this.baseUrl = baseUrl;
    this.userId = userId;
    this.logger = logger;
    this.receivedEvents = [];
  }

  /** 페어링 코드 발급 (master PairingScreen 미러). */
  async issuePairCode() {
    const { Api } = await import('../net/api.js');
    const res = await Api.pairCode(this.baseUrl, this.userId);
    this.pairCode = res.code;
    return res.code;
  }

  /** 식물 등록 (master 식물 추가). */
  async createPlant({ slaveId, name, species }) {
    const { Api } = await import('../net/api.js');
    const res = await Api.createPlant(this.baseUrl, { userId: this.userId, slaveId, name, species });
    return res.plantId;
  }

  /** 카메라 설치 위치 정책 원격 설정 (camera_mount). */
  async setCameraMount(slaveId, cameraMount) {
    const { Api } = await import('../net/api.js');
    return Api.setPolicy(this.baseUrl, slaveId, { cameraMount });
  }

  /** master SSE 구독 개시 — photo_ready / health_change 실시간 수신. */
  async subscribe(slaveId) {
    this.sse = openSse(this.baseUrl, `/api/commands/sse/master/${slaveId}`, {
      onEvent: (ev) => {
        this.receivedEvents.push(ev);
        if (ev.type === 'photo_ready') this.logger(`[SSE] photo_ready 수신: ${ev.url} (commandId=${ev.commandId})`);
        if (ev.type === 'health_change') this.logger(`[SSE] health_change 수신: ${ev.payload?.fromKo} → ${ev.payload?.toKo}`);
      },
    });
    await sleep(150); // 연결 안정화
  }

  unsubscribe() {
    try { this.sse?.close(); } catch {}
  }

  /** 원격 촬영 지시 → photo_ready 대기 (LiveCameraScreen.request 미러). */
  async requestPhoto(slaveId, plantId = null, timeoutMs = 15000) {
    const { Api } = await import('../net/api.js');
    const { commandId } = await Api.sendCommand(this.baseUrl, { slaveId, plantId, action: 'capture_photo' });
    const start = Date.now();
    for (;;) {
      const ev = this.receivedEvents.find((e) => e.type === 'photo_ready' && e.commandId === commandId);
      if (ev) return { commandId, event: ev };
      if (Date.now() - start > timeoutMs) throw new Error(`photo_ready 대기 시간 초과 (commandId=${commandId})`);
      await sleep(60);
    }
  }

  /** 업로드된 사진 다운로드 + 가로/세로 확인 (자동 방향 촬영 검증). */
  async downloadPhoto(urlPath) {
    const { Api } = await import('../net/api.js');
    const { buf, contentType } = await Api.download(this.baseUrl, urlPath);
    const size = pngSize(buf);
    return { buf, contentType, ...size, orientation: size.width > size.height ? 'landscape' : 'portrait' };
  }

  /** 알림 센터 조회 (master NotificationScreen 폴링 미러). */
  async notifications() {
    const { Api } = await import('../net/api.js');
    const res = await Api.notifications(this.baseUrl, this.userId);
    return res.notifications;
  }

  /** 식물 최신 분석 조회 (PlantDetailScreen health 카드 미러). */
  async latestAnalysis(plantId) {
    const { Api } = await import('../net/api.js');
    return Api.latestAnalysis(this.baseUrl, plantId);
  }
}
