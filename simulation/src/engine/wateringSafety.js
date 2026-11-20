// W5 — 관수 안전 상태머신 (android/slave service/FarmerEngine.kt 미러).
//
// PC 시뮬레이션에 이식한 이유: 실기기 BLE/범람 시나리오를 E2E 로 검증하기
// 어렵기 때문에, 결정론적 시계/액추에이터로 "같은 규칙"을 테스트할 수 있게 한다.
// FarmerEngine 의 W5 수정(범람 방지)과 동일한 규칙:
//   - 자동 관수: needWater 상승 엣지(히스테리시스) + 마지막 관수 후 30분 쿨다운.
//     성공 시에만 엣지를 소모(실패 시 재시도 가능), 쿨다운 기준점은 시도 시점에 갱신.
//   - 모든 관수(자동/수동/마스터 지시)가 쿨다운 기준점을 갱신한다.
//   - water_failed: 밸브가 실제로 열리지 않았을 때 — BLE 미연결=hardware_offline,
//     제어 예외=control_error. 거짓 "관수 완료" 기록을 막는다.
//   - battery_low: 20% 이하 하락 엣지 + 1시간 쿨다운.
export const AUTO_WATER_COOLDOWN_MS = 30 * 60 * 1000;
export const BATTERY_LOW_THRESHOLD = 20;
export const BATTERY_LOW_COOLDOWN_MS = 60 * 60 * 1000;
export const AUTO_WATER_BASE_ML = 300;
export const AUTO_WATER_MIN_ML = 50;
export const AUTO_WATER_MAX_ML = 2000;

export class WateringSafety {
  /**
   * @param {{ now?: () => number,
   *           water?: (opts: { source: string, amount: number, plantId: string|null,
   *                     weatherFactor: number, confidence: number })
   *                    => Promise<{ ok: boolean, reason?: 'hardware_offline'|'control_error' }>,
   *           reportEvent?: (type: string, payload: object, plantId?: string|null) => unknown|Promise<unknown>,
   *           wateringLog?: (plantId: string|null, amount: number, source: string, weatherFactor: number) => unknown|Promise<unknown>,
   *           log?: (msg: string) => void }} opts
   * water — 밸브 액추에이터. { ok: true } = 밸브가 실제로 열림.
   */
  constructor(opts = {}) {
    this.now = opts.now || (() => Date.now());
    this.water = opts.water || (async () => ({ ok: true }));
    this.reportEvent = opts.reportEvent || (async () => {});
    this.wateringLog = opts.wateringLog || (async () => {});
    this.log = opts.log || (() => {});
    // FarmerEngine 필드 미러
    this.lastNeedWaterState = false;
    this.lastAutoWaterMs = 0;
    this.lastBatteryBelowThreshold = false;
    this.lastBatteryLowNotify = 0;
    this.waterLog = []; // 시뮬레이션 전용 — 관수 이력 (검증용)
  }

  /** FarmerEngine.tick 미러 — needWater 상승 엣지 + 30분 쿨다운. */
  async tick({ needWater, autoOn = true, confidence = 1, plantId = null, weatherFactor = 1 } = {}) {
    if (needWater && autoOn) {
      const nowMs = this.now();
      if (!this.lastNeedWaterState && nowMs - this.lastAutoWaterMs >= AUTO_WATER_COOLDOWN_MS) {
        this.log('[W5] 수분 부족(상승 엣지) → 자동 관수 시도 (쿨다운 30분)');
        this.lastAutoWaterMs = nowMs; // 시도 시점 갱신(실패 포함) — FarmerEngine 과 동일
        const ok = await this.attemptWater({ source: 'auto', confidence, plantId, weatherFactor });
        if (ok) this.lastNeedWaterState = true; // 성공 시에만 엣지 소모
      }
    }
    if (!needWater) this.lastNeedWaterState = false;
  }

  /** FarmerEngine.doWater 미러 — 수동/마스터 지시 관수는 쿨다운 기준점 갱신 후 시도. */
  async doWater({ source, confidence = 1, amountMl = null, plantId = null, weatherFactor = 1 }) {
    this.lastAutoWaterMs = this.now();
    return this.attemptWater({ source, confidence, plantId, amountOverride: amountMl, weatherFactor });
  }

  /** FarmerEngine.attemptWater 미러 — 밸브 제어 + 성공/실패 보고. @returns 밸브가 실제로 열렸는지 */
  async attemptWater({ source, confidence = 1, plantId = null, amountOverride = null, weatherFactor = 1 }) {
    const baseAmount = amountOverride ?? AUTO_WATER_BASE_ML;
    const amount = source === 'auto'
      ? Math.min(AUTO_WATER_MAX_ML, Math.max(AUTO_WATER_MIN_ML, Math.round(baseAmount * weatherFactor)))
      : baseAmount;
    let res;
    try {
      res = await this.water({ source, amount, plantId, weatherFactor, confidence });
    } catch {
      res = { ok: false, reason: 'control_error' };
    }
    const ok = !!(res && res.ok);
    if (ok) {
      this.waterLog.push({ source, amount, plantId, at: this.now() });
      await this.wateringLog(plantId, amount, source, weatherFactor);
      await this.reportEvent(source === 'auto' ? 'auto_water' : 'manual_water', {
        amount: String(amount),
        weatherFactor: weatherFactor.toFixed(2),
        confidence: confidence.toFixed(2),
      }, plantId);
    } else {
      const reason = (res && res.reason) || 'hardware_offline';
      this.log(`[W5] 관수 실패 (${reason}) → water_failed 보고`);
      await this.reportEvent('water_failed', {
        reason,
        requested: String(amount),
        source,
      }, plantId);
    }
    return ok;
  }

  /** FarmerEngine.maybeNotifyLowBattery 미러 — 20% 이하 하락 엣지 + 1시간 쿨다운. */
  async notifyLowBattery(batt, plantId = null) {
    if (batt == null) return;
    if (batt > BATTERY_LOW_THRESHOLD) {
      this.lastBatteryBelowThreshold = false; // 정상 복귀 → 다음 하락 엣지 준비
      return;
    }
    const nowMs = this.now();
    if (!this.lastBatteryBelowThreshold && nowMs - this.lastBatteryLowNotify >= BATTERY_LOW_COOLDOWN_MS) {
      this.lastBatteryBelowThreshold = true;
      this.lastBatteryLowNotify = nowMs;
      this.log(`[W5] 배터리 부족 (${batt}%) → battery_low 보고`);
      await this.reportEvent('battery_low', {
        level: String(batt),
        threshold: String(BATTERY_LOW_THRESHOLD),
      }, plantId);
    }
  }
}
