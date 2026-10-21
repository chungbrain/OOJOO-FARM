// F3 — FarmerSim 건강상태 전이 감지 단위 테스트 (백엔드 없이 mock API).
// 검증 원칙: 이벤트의 from/to 는 '모델이 실제 판정한 등급'(기록된 분석 리포트)과
// 일치해야 한다 (모델 정확도 자체는 S1/S2 시나리오가 검증).
// 전이 확정 규칙: 새 상태가 연속 confirmCycles(2) 회 관측될 때만 알림 —
// 단일 사이클 오분류(플리커)는 알림을 유발하지 않는다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FarmerSim } from '../src/engine/farmerSim.js';
import { CameraSim } from '../src/engine/cameraSim.js';
import { LABEL_KO as GRADE_KO } from '../src/models/jevHealth.js';

function makeMockApi({ failEvent = false } = {}) {
  const sent = { events: [], analyses: [] };
  return {
    sent,
    setFailEvent(v) { failEvent = v; },
    baseUrl: 'http://mock.test',
    slaveId: 'sim-slave-1',
    authHeaders: () => ({}),
    heartbeat: async () => ({ ok: true }),
    policy: async () => ({ camera_mount: 'auto', roi_interval: 20 }),
    reportEvent: async (type, payload, plantId) => {
      if (failEvent) throw new Error('offline');
      sent.events.push({ type, payload, plantId });
      return { eventId: 'e' + sent.events.length };
    },
    reportAnalysis: async (plantId, analysis) => {
      sent.analyses.push({ plantId, analysis });
      return { analysisId: 'a' + sent.analyses.length };
    },
    slavePlants: async () => ({ plants: [{ id: 'plant-1', name: '심바질' }] }),
    pendingCommands: async () => ({ commands: [] }),
    commandDone: async () => ({ ok: true }),
    wateringLog: async () => ({ logId: 'w1' }),
    uploadPhoto: async () => ({ photoId: 'p1' }),
  };
}

function makeWorld() {
  return {
    plants: [{ plantId: 'plant-1', name: '심바질', species: 'basil', roi: { x: 0, y: 0, w: 1, h: 1 }, grade: 'healthy', pest: 'none' }],
    displayRotation: 0,
    battery: 90,
  };
}

/** 식물별 마지막 분석(모델 판정) 조회 */
function lastAnalysis(api, plantId) {
  const rows = api.sent.analyses.filter((a) => a.plantId === plantId);
  return rows[rows.length - 1]?.analysis;
}

/** confirmCycles 회 연속 관측 — world 상태를 유지한 채 모니터링 사이클 반복 */
async function observe(farmer, times) {
  for (let i = 0; i < times; i++) await farmer.runRoiMonitoring();
}

test('전이 감지: 첫 관찰 기준선 → 유지 무알림 → 연속 2회 관측 시 전이 확정·알림', async () => {
  const api = makeMockApi();
  const world = makeWorld();
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });

  await observe(farmer, 2); // 첫 관찰(기준선) + 유지 — 알림 없음
  assert.equal(api.sent.events.length, 0);
  const baseline = lastAnalysis(api, 'plant-1');
  assert.ok(baseline, '분석 리포트는 매 사이클 업로드');

  // 시각적으로 뚜렷한 전이 — 등급 악화 + 해충 심각 발생
  world.plants[0].grade = 'very_poor';
  world.plants[0].pest = 'severe';
  await observe(farmer, 1); // 1회 관측 — 아직 미확정 (플리커 방지)
  assert.equal(api.sent.events.length, 0, '1회 관측만으로는 알림 없음');

  await observe(farmer, 1); // 2회 연속 관측 — 전이 확정
  assert.equal(api.sent.events.length, 1);
  const ev = api.sent.events[0];
  const after = lastAnalysis(api, 'plant-1');
  assert.equal(ev.type, 'health_change');
  assert.equal(ev.payload.from, baseline.healthGrade);
  assert.equal(ev.payload.to, after.healthGrade);
  assert.equal(ev.payload.fromKo, GRADE_KO[baseline.healthGrade]);
  assert.equal(ev.payload.toKo, after.healthGradeKo);
  assert.equal(ev.payload.modelId, 'health_grade');
  assert.equal(ev.payload.pest.to, after.pestLevel);
  assert.equal(ev.payload.pest.levelKo, after.pestLevelKo);
  assert.equal(ev.payload.pest.modelId, 'pest');
  assert.equal(ev.plantId, 'plant-1');

  await observe(farmer, 1); // 유지 — 무알림
  assert.equal(api.sent.events.length, 1);
});

test('안티-플리커: 단일 사이클 오분류(변화 1회 → 복귀)는 알림을 유발하지 않음', async () => {
  const api = makeMockApi();
  const world = makeWorld();
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });
  await observe(farmer, 2); // 기준선 healthy

  // 1사이클만 다른 상태 → 원상 복귀 (플리커)
  world.plants[0].grade = 'very_poor';
  await observe(farmer, 1);
  world.plants[0].grade = 'healthy';
  await observe(farmer, 2);
  assert.equal(api.sent.events.length, 0, '플리커는 알림 없음');

  // 이후 실제 전이는 정상 감지
  world.plants[0].grade = 'very_poor';
  await observe(farmer, 2);
  assert.equal(api.sent.events.length, 1);
  assert.equal(api.sent.events[0].payload.from, 'healthy');
});

test('전이 감지: 등급은 유지, 해충만 전이해도 health_change (pest 필드만)', async () => {
  const api = makeMockApi();
  const world = makeWorld();
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });
  await observe(farmer, 2);
  const baseline = lastAnalysis(api, 'plant-1');

  world.plants[0].pest = 'severe';
  await observe(farmer, 2); // 연속 2회 관측 → 확정
  assert.equal(api.sent.events.length, 1);
  const ev = api.sent.events[0];
  const after = lastAnalysis(api, 'plant-1');
  assert.equal(ev.payload.from, baseline.healthGrade);
  assert.equal(ev.payload.to, after.healthGrade);
  assert.equal(ev.payload.pest.to, after.pestLevel);
});

test('오프라인 큐: 이벤트 전송 실패 → 큐 적재 → 재연결 시 flush', async () => {
  const api = makeMockApi({ failEvent: true });
  const world = makeWorld();
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });
  await observe(farmer, 2); // 기준선

  world.plants[0].grade = 'very_poor';
  await observe(farmer, 2); // 전이 확정 — 전송 실패 → 큐 적재
  assert.equal(api.sent.events.length, 0);
  assert.equal(farmer.eventQueue.length, 1);

  api.setFailEvent(false);
  await farmer.flushQueue(); // 하트비트 성공 후 flush
  assert.equal(farmer.eventQueue.length, 0);
  assert.equal(api.sent.events.length, 1);
  assert.equal(api.sent.events[0].payload.to, lastAnalysis(api, 'plant-1').healthGrade);
});

test('다식물 ROI: 식물별 독립 전이 감지', async () => {
  const api = makeMockApi();
  const world = makeWorld();
  world.plants.push({ plantId: 'plant-2', name: '심고추', species: 'pepper', roi: { x: 0.5, y: 0, w: 0.5, h: 1 }, grade: 'healthy', pest: 'none' });
  const farmer = new FarmerSim({ api, camera: new CameraSim(), world, cycleMs: 10, logger: () => {} });
  await observe(farmer, 2); // 기준선 2식물
  assert.equal(api.sent.events.length, 0);

  world.plants[1].grade = 'very_poor'; // 2번 식물만 악화
  await observe(farmer, 2); // 연속 2회 → 확정
  assert.equal(api.sent.events.length, 1);
  assert.equal(api.sent.events[0].plantId, 'plant-2');
  assert.equal(api.sent.events[0].payload.to, lastAnalysis(api, 'plant-2').healthGrade);
  // 두 식물 모두 ROI별 분석 리포트 업로드
  assert.ok(api.sent.analyses.some((a) => a.plantId === 'plant-1'));
  assert.ok(api.sent.analyses.some((a) => a.plantId === 'plant-2'));
});
