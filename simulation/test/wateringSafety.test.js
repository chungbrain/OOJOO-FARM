// W5 — 관수 안전 상태머신 테스트 (FarmerEngine.kt 미러의 결정론적 검증).
// 실기기에서 E2E 검증이 어려운 범람 방지 규칙을 주입된 시계/액추에이터로 증명:
//   - needWater 지속 → 관수 1회 (히스테리시스: 상승 엣지만)
//   - 30분 쿨다운 (수동 관수도 기준점 갱신)
//   - BLE 미연결/제어 오류 → water_failed + 엣지 미소모 + 재시도
//   - battery_low: 20% 하락 엣지 1회 + 1시간 쿨다운
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FarmerSim } from '../src/engine/farmerSim.js';
import { CameraSim } from '../src/engine/cameraSim.js';
import {
  WateringSafety, AUTO_WATER_COOLDOWN_MS, BATTERY_LOW_THRESHOLD,
} from '../src/engine/wateringSafety.js';

const MIN = 60 * 1000;
const T0 = 1_000_000_000; // 임의 기준 시각

function makeSafety({ bleOnline = true, controlError = false } = {}) {
  let t = T0;
  const hw = { bleOnline, controlError };
  const events = [];
  const waterLogs = [];
  const safety = new WateringSafety({
    now: () => t,
    water: async () => ({
      ok: hw.bleOnline && !hw.controlError,
      reason: hw.bleOnline ? (hw.controlError ? 'control_error' : undefined) : 'hardware_offline',
    }),
    reportEvent: async (type, payload, plantId) => { events.push({ type, payload, plantId }); },
    wateringLog: async (plantId, amount, source, factor) => { waterLogs.push({ plantId, amount, source, factor }); },
  });
  return {
    safety, events, waterLogs,
    setBle: (v) => { hw.bleOnline = v; },
    setControlError: (v) => { hw.controlError = v; },
    at: (ms) => { t = ms; },
    forward: (ms) => { t += ms; },
    autoWaters: () => events.filter((e) => e.type === 'auto_water'),
  };
}

test('지속적 needWater → 자동 관수 1회만 (범람 방지)', async () => {
  const { safety, autoWaters, forward } = makeSafety();
  // needWater 가 true 로 유지되는 동안 틱 20회 (총 10시간 — 쿨다운을 수십 번 넘김)
  for (let i = 0; i < 20; i++) {
    await safety.tick({ needWater: true });
    forward(AUTO_WATER_COOLDOWN_MS);
  }
  assert.equal(autoWaters().length, 1, '상승 엣지 1회 → 관수 1회, 재관수 없음');
});

test('쿨다운: 재상승 엣지라도 마지막 관수 후 30분 안에 재관수 안 함', async () => {
  const { safety, autoWaters, forward } = makeSafety();
  await safety.tick({ needWater: true });              // t0: 관수 1회 (엣지 소모)
  forward(1 * MIN);
  await safety.tick({ needWater: false });             // 엣지 리셋
  forward(1 * MIN);
  await safety.tick({ needWater: true });              // t0+2분: 새 상승 엣지만데 쿨다운 중
  assert.equal(autoWaters().length, 1, '쿨다운 만료 전 재관수 없음');
  forward(29 * MIN);
  await safety.tick({ needWater: true });              // t0+31분: 쿨다운 만료
  assert.equal(autoWaters().length, 2);
});

test('수동 관수도 쿨다운 기준점 갱신 — 자동 관수 억제', async () => {
  const { safety, autoWaters, waterLogs, forward } = makeSafety();
  await safety.tick({ needWater: true });              // t0: 자동 관수
  forward(1 * MIN);
  await safety.tick({ needWater: false });
  forward(9 * MIN);
  await safety.doWater({ source: 'manual', amountMl: 300 }); // t0+10분: 수동 관수
  assert.equal(waterLogs.length, 2);
  forward(25 * MIN);
  await safety.tick({ needWater: true });              // t0+35분: 수동 후 25분 → 억제
  assert.equal(autoWaters().length, 1, '수동 관수가 기준점을 갱신했으므로 아직 쿨다운');
  forward(6 * MIN);
  await safety.tick({ needWater: true });              // t0+41분: 수동 후 31분 → 관수
  assert.equal(autoWaters().length, 2);
});

test('BLE 미연결 → water_failed + 엣지 미소모 + 쿨다운 후 재시도 → 재연결 시 성공', async () => {
  const h = makeSafety({ bleOnline: false });
  const { safety, events, forward, setBle } = h;

  await safety.tick({ needWater: true });              // t0: 실패 — hardware_offline
  assert.equal(events.length, 1);
  assert.equal(events[0].type, 'water_failed');
  assert.equal(events[0].payload.reason, 'hardware_offline');
  assert.equal(events[0].payload.source, 'auto');

  forward(5 * MIN);
  await safety.tick({ needWater: true });              // 쿨다운 중 — 재시도 자체가 없음
  assert.equal(events.length, 1);

  forward(26 * MIN);
  await safety.tick({ needWater: true });              // t0+31분: 쿨다운 만료, 아직 미연결 → 재시도
  assert.equal(events.length, 2);
  assert.equal(events[1].type, 'water_failed', '엣지가 소모되지 않아 쿨다운 만료 후 재시도');

  setBle(true);
  forward(30 * MIN);
  await safety.tick({ needWater: true });              // t0+61분: 재연결 → 성공, 엣지 소모
  assert.equal(events.length, 3);
  assert.equal(events[2].type, 'auto_water');

  forward(1 * MIN);
  await safety.tick({ needWater: true });              // 소모된 엣지 → 추가 관수 없음
  assert.equal(events.length, 3);
  assert.equal(safety.waterLog.length, 1, '실제 관수(수신 기록)는 딱 1회');
});

test('제어 오류(BLE 연결됨) → water_failed(control_error)', async () => {
  const h = makeSafety({ controlError: true });
  await h.safety.tick({ needWater: true });
  assert.equal(h.events.length, 1);
  assert.equal(h.events[0].type, 'water_failed');
  assert.equal(h.events[0].payload.reason, 'control_error');
  assert.equal(h.safety.waterLog.length, 0);
});

test('battery_low: 하락 엣지 1회 + 1시간 쿨다운 + 정상 복귀 후 재알림', async () => {
  const { safety, events, at } = makeSafety();
  const lows = () => events.filter((e) => e.type === 'battery_low');

  await safety.notifyLowBattery(15);                   // 하락 엣지 → 알림 1
  assert.equal(lows().length, 1);
  assert.equal(lows()[0].payload.level, '15');
  assert.equal(lows()[0].payload.threshold, String(BATTERY_LOW_THRESHOLD));

  at(T0 + 30 * MIN);
  await safety.notifyLowBattery(12);                   // 임계 이하 지속 — 무알림
  assert.equal(lows().length, 1);

  at(T0 + 40 * MIN);
  await safety.notifyLowBattery(80);                   // 정상 복귀 → 엣지 리셋
  assert.equal(lows().length, 1);

  at(T0 + 50 * MIN);
  await safety.notifyLowBattery(10);                   // 새 하락 엣지만데 1시간 쿨다운 미만
  assert.equal(lows().length, 1);

  at(T0 + 70 * MIN);
  await safety.notifyLowBattery(10);                   // 쿨다운 만료 → 알림 2
  assert.equal(lows().length, 2);
  assert.equal(lows()[1].payload.level, '10');

  await safety.notifyLowBattery(null);                 // null(측정 실패) — 무시
  assert.equal(lows().length, 2);
});

test('자동 관수량: 날씨 가중 + 50..2000ml 클램프', async () => {
  const h = makeSafety();
  await h.safety.attemptWater({ source: 'auto', confidence: 0.9, weatherFactor: 0.1 }); // 30ml → 50
  assert.equal(h.events[0].type, 'auto_water');
  assert.equal(h.events[0].payload.amount, '50');
  assert.equal(h.events[0].payload.confidence, '0.90');
  assert.equal(h.events[0].payload.weatherFactor, '0.10');
  await h.safety.attemptWater({ source: 'auto', weatherFactor: 10 }); // 3000ml → 2000
  assert.equal(h.events[1].payload.amount, '2000');
  assert.equal(h.waterLogs.length, 2);
  assert.equal(h.waterLogs[0].amount, 50);
  assert.equal(h.waterLogs[1].amount, 2000);
});

// ---- FarmerSim 통합: 'water' 명령이 안전 상태머신을 실제로 경유하는지 ----

function makeMockApi() {
  const sent = { events: [], waterings: [] };
  return {
    sent,
    baseUrl: 'http://mock.test',
    slaveId: 'sim-slave-1',
    authHeaders: () => ({}),
    reportEvent: async (type, payload, plantId) => { sent.events.push({ type, payload, plantId }); return { eventId: 'e' }; },
    wateringLog: async (o) => { sent.waterings.push(o); return { logId: 'w1' }; },
    commandDone: async () => ({ ok: true }),
    policy: async () => ({ camera_mount: 'auto', roi_interval: 20 }),
    heartbeat: async () => ({ ok: true }),
    reportAnalysis: async () => ({ analysisId: 'a1' }),
    slavePlants: async () => ({ plants: [] }),
    pendingCommands: async () => ({ commands: [] }),
    uploadPhoto: async () => ({ photoId: 'p1' }),
  };
}

test('FarmerSim: water 명령 → 안전 상태머신 경유 (쿨다운 갱신 + manual_water + 관수 로그)', async () => {
  const api = makeMockApi();
  const world = { plants: [], displayRotation: 0, battery: 90 };
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });

  await farmer.handleCommand({ id: 'cmd-1', action: 'water', plant_id: 'plant-1', amount_ml: 250 });
  const m = api.sent.events.find((e) => e.type === 'manual_water');
  assert.ok(m, 'manual_water 이벤트');
  assert.equal(m.payload.amount, '250');
  assert.equal(api.sent.waterings.length, 1);
  assert.equal(api.sent.waterings[0].amountMl, 250);

  // 직후(쿨다운 내) 자동 틱은 관수하지 않는다
  await farmer.safety.tick({ needWater: true });
  assert.equal(api.sent.events.filter((e) => e.type === 'auto_water').length, 0);
});

test('FarmerSim: BLE 미연결 world → water 명령도 water_failed (관수 로그 없음)', async () => {
  const api = makeMockApi();
  const world = { plants: [], displayRotation: 0, battery: 90, bleOnline: false };
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });

  await farmer.handleCommand({ id: 'cmd-2', action: 'water', plant_id: 'plant-1', amount_ml: 100 });
  const f = api.sent.events.find((e) => e.type === 'water_failed');
  assert.ok(f, 'water_failed 이벤트');
  assert.equal(f.payload.reason, 'hardware_offline');
  assert.equal(f.payload.requested, '100');
  assert.equal(api.sent.waterings.length, 0, '밸브가 안 열렸으므로 관수 로그 금지');
  assert.equal(farmer.valveLog.length, 1);
  assert.equal(farmer.valveLog[0].opened, false);
});
