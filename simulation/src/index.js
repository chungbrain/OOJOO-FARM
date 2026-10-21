#!/usr/bin/env node
// F5 — PC 시뮬레이션 시나리오 러너 (docs/farmer-features.md §5).
//
// S1: Jev 건강등급 판단 정확도 (오프라인 — 모델 로컬 검증)
// S2: 해충발생 판단 정확도 + 건강등급 독립성 (오프라인)
// S3: 건강 악화/회복 → health_change → master 실시간 알림 E2E (백엔드 연결)
// S4: camera_mount 정책 기반 가로/세로 자동 촬영 E2E (백엔드 연결)
//
// 사용:
//   node src/index.js                       # 전체 시나리오 (백엔드 :4000 필요)
//   node src/index.js --scenario s1,s2      # 오프라인 모델 검증만
//   node src/index.js --base http://127.0.0.1:4000 --n 50
import process from 'node:process';

import * as jevHealth from './models/jevHealth.js';
import * as pestNet from './models/pestNet.js';
import { renderScene, cropSample48, GRADE_LABELS, PEST_LABELS, SPECIES } from './vision/plantScene.js';
import { CameraSim } from './engine/cameraSim.js';
import { FarmerSim } from './engine/farmerSim.js';
import { MasterSim } from './master/masterSim.js';
import { Api, sleep } from './net/api.js';

const SPECIES_IDS = Object.keys(SPECIES);
const results = [];

function parseArgs() {
  const args = { base: process.env.SIM_BASE || 'http://127.0.0.1:4000', scenario: 'all', n: 30 };
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--base') args.base = argv[++i];
    else if (argv[i] === '--scenario') args.scenario = argv[++i];
    else if (argv[i] === '--n') args.n = Number(argv[++i]);
    else if (argv[i] === '--offline') args.scenario = 's1,s2';
    else if (argv[i] === '--help' || argv[i] === '-h') {
      console.log('usage: node src/index.js [--base URL] [--scenario all|s1|s2|s3|s4|s1,s2] [--n 샘플수] [--offline]');
      process.exit(0);
    }
  }
  return args;
}

const pct = (v) => `${(v * 100).toFixed(1)}%`;
const log = (msg) => console.log(msg);

function record(id, name, pass, detail) {
  results.push({ id, name, pass, detail });
  log(`  → ${pass ? 'PASS' : 'FAIL'} ${detail}`);
}

// ─── S1: JevHealthNet 건강등급 판단 ───
// farmer 모니터링과 동일 경로: upright 프레임(480x640, displayRotation 0) 렌더 → 48 다운샘플
const FRAME_W = 480;
const FRAME_H = 640;

async function scenarioS1(n) {
  log('\n[S1] JevHealthNet — 식물 건강등급 5단계 판단 (초경량, 오프라인)');
  let hit = 0, adjacent = 0, total = 0, far = 0;
  const perGrade = {};
  const confusion = {};
  for (const grade of GRADE_LABELS) {
    let ok = 0;
    const gi = GRADE_LABELS.indexOf(grade);
    for (let i = 0; i < n; i++) {
      const species = SPECIES_IDS[(i * 3 + gi) % SPECIES_IDS.length];
      const scene = renderScene({ species, width: FRAME_W, height: FRAME_H, grade, pest: 'none', seed: 101 + i * 7 + gi * 991 });
      const rgb48 = cropSample48(scene.rgb, scene.width, scene.height, { x: 0, y: 0, w: scene.width, h: scene.height });
      const res = jevHealth.infer(rgb48);
      const got = GRADE_LABELS.indexOf(res.grade);
      confusion[`${grade}→${res.grade}`] = (confusion[`${grade}→${res.grade}`] ?? 0) + 1;
      if (got === gi) { ok++; hit++; }
      else if (Math.abs(got - gi) === 1) adjacent++;
      else far++;
      total++;
    }
    perGrade[grade] = ok / n;
  }
  log(`  등급별 일치율: ${GRADE_LABELS.map((g) => `${jevHealth.LABEL_KO[g]} ${pct(perGrade[g])}`).join(' / ')}`);
  log(`  인접 등급 오차(1단계): ${pct(adjacent / total)} / 2단계+ 오차: ${pct(far / total)}`);
  const worst = Object.entries(confusion).sort((a, b) => b[1] - a[1]).slice(0, 3)
    .map(([k, v]) => `${k}(${v}회)`).join(', ');
  log(`  주요 혼동: ${worst}`);
  // 기준(의미 기반): 알림 대상 등급(poor/very_poor)의 정확도와 심각 혼동(2단계+) 부재가
  // 제품 요구사항(F3 변화 알림 정확성)을 결정한다. 매우건강↔건강은 연성 경계(알림 무해)로
  // 인접 등급 기준(60%)을 별도로 적용한다.
  const alarmOk = perGrade.poor >= 0.85 && perGrade.very_poor >= 0.85;
  const softOk = perGrade.very_healthy >= 0.6 && perGrade.healthy >= 0.6 && perGrade.fair >= 0.6;
  const farOk = far / total <= 0.02;
  log(`  알림 등급(나쁨 ${pct(perGrade.poor)}/매우나쁨 ${pct(perGrade.very_poor)}) ≥85%: ${alarmOk ? '충족' : '미달'}`);
  record('S1', '건강등급 판단', hit / total >= 0.85 && alarmOk && softOk && farOk,
    `전체 일치율 ${pct(hit / total)} (기준 85%+), 알림 등급 ${alarmOk ? '충족' : '미달'}, 심각 혼동 ${pct(far / total)}`);
}

// ─── S2: PestNet 해충발생 판단 + 건강등급 독립성 (오프라인) ───
async function scenarioS2(n) {
  log('\n[S2] PestNet — 해충발생 4단계 판단 (건강등급과 별도 모델, 오프라인)');
  const grades = ['healthy', 'fair', 'poor'];
  let hit = 0, total = 0;
  const perLevel = {};
  for (const level of PEST_LABELS) {
    let ok = 0;
    const li = PEST_LABELS.indexOf(level);
    for (let i = 0; i < n; i++) {
      const species = SPECIES_IDS[(i * 5 + li * 2) % SPECIES_IDS.length];
      const grade = grades[(i + li) % grades.length];
      const scene = renderScene({ species, width: FRAME_W, height: FRAME_H, grade, pest: level, seed: 601 + i * 11 + li * 83 });
      const rgb48 = cropSample48(scene.rgb, scene.width, scene.height, { x: 0, y: 0, w: scene.width, h: scene.height });
      const res = pestNet.infer(rgb48);
      if (res.level === level) { ok++; hit++; }
      total++;
    }
    perLevel[level] = ok / n;
  }
  log(`  단계별 일치율: ${PEST_LABELS.map((l) => `${pestNet.LABEL_KO[l]} ${pct(perLevel[l])}`).join(' / ')}`);

  // 독립성 — 건강이 나빠도(황변/시듦) 해충 없음은 '없음'으로, 건강해도 구멍은 심각으로 판정해야 함
  const indep = {};
  for (const [grade, level] of [['poor', 'none'], ['very_poor', 'none'], ['healthy', 'severe'], ['very_healthy', 'severe']]) {
    let ok = 0;
    for (let i = 0; i < n; i++) {
      const species = SPECIES_IDS[(i * 7) % SPECIES_IDS.length];
      const scene = renderScene({ species, width: FRAME_W, height: FRAME_H, grade, pest: level, seed: 901 + i * 13 + grade.length * 29 });
      const rgb48 = cropSample48(scene.rgb, scene.width, scene.height, { x: 0, y: 0, w: scene.width, h: scene.height });
      const res = pestNet.infer(rgb48);
      if (res.level === level) ok++;
    }
    indep[`${grade}+${level}`] = ok / n;
  }
  log(`  독립성 검증: ${Object.entries(indep).map(([k, v]) => `${k} ${pct(v)}`).join(' / ')}`);
  const indepOk = Object.values(indep).every((v) => v >= 0.8);
  record('S2', '해충발생 판단+독립성', hit / total >= 0.8 && indepOk,
    `전체 일치율 ${pct(hit / total)} (기준 80%+), 독립성 ${indepOk ? '충족' : '미달'}`);
}

// ─── 공통 E2E 준비: 사용자/페어링/식물/farmer 기동 ───
async function setupE2E(base, tag) {
  const user = await Api.createUser(base, { nickname: `sim-${tag}-${Date.now() % 100000}`, region: 'Seoul' });
  const master = new MasterSim({ baseUrl: base, userId: user.id, logger: (m) => log(`  [master] ${m}`) });
  const code = await master.issuePairCode();
  const world = {
    plants: [{ plantId: null, name: `심바질-${tag}`, species: 'basil', roi: { x: 0, y: 0, w: 1, h: 1 }, grade: 'healthy', pest: 'none' }],
    displayRotation: 0,
    battery: 92,
  };
  const camera = new CameraSim();
  const farmer = new FarmerSim({
    api: new Api(base), camera, world, cycleMs: 350,
    logger: (m) => log(`  [farmer] ${m}`),
  });
  const creds = await farmer.pair(base, code);
  const plantId = await master.createPlant({ slaveId: creds.slaveId, name: world.plants[0].name, species: 'basil' });
  await farmer.fetchPlants();
  return { master, farmer, world, creds, plantId };
}

// ─── S3: 건강 악화/회복 → health_change → master 실시간 알림 (E2E) ───
async function scenarioS3(base) {
  log('\n[S3] 건강상태 변화 → master 알림 (E2E — 실제 백엔드 연결)');
  const { master, farmer, world, creds, plantId } = await setupE2E(base, 's3');
  await master.subscribe(creds.slaveId);
  await farmer.start();

  // 타임라인: 건강(기준선) → 보통(+해충 경미) → 매우나쁨(+심각) → 건강(회복)
  // very_healthy↔healthy 경계(가장 모호한 구간)에 의존하지 않도록 모두 뚜렷한 전이만 사용.
  // 전이는 연속 2회 관측으로 확정되므로 상태당 스텝×2 + 스텝당 1300ms(≥3 사이클) 유지.
  const timeline = [
    ['healthy', 'none'], ['healthy', 'none'],
    ['fair', 'light'], ['fair', 'light'],
    ['very_poor', 'severe'], ['very_poor', 'severe'],
    ['healthy', 'none'],
  ];
  for (const [grade, pest] of timeline) {
    world.plants[0].grade = grade;
    world.plants[0].pest = pest;
    await sleep(1300);
  }
  await sleep(1300); // 마지막 상태(회복) 확정 관측 + 최신 분석 반영
  await farmer.stop();
  master.unsubscribe();

  const events = master.receivedEvents.filter((e) => e.type === 'health_change');
  log(`  master 수신 health_change: ${events.length}건`);
  for (const e of events) {
    log(`    - ${e.payload.fromKo} → ${e.payload.toKo} (신뢰도 ${(e.payload.confidence * 100).toFixed(0)}%)` +
      (e.payload.pest ? ` 해충:${e.payload.pest.levelKo}` : ''));
  }

  // 등급 전이(악화/회복)와 해충 전이(페드로 라이딩)를 구분해 검증 —
  // 베이스라인에서 none↔light 연성 경계의 확정된 해충 전이는 등급 체인 검증에서 제외.
  const gradeEvents = events.filter((e) => e.payload.from !== e.payload.to);
  const checks = [];
  const push = (name, ok) => { checks.push(ok); log(`  ✓ ${name}: ${ok ? '통과' : '실패'}`); };

  push('최소 3회 전이 이벤트 수신 (악화 2 + 회복 1)', events.length >= 3);
  push('첫 등급 전이: 건강 → 보통', gradeEvents.length > 0 && gradeEvents[0].payload.from === 'healthy' && gradeEvents[0].payload.to === 'fair');
  push('마지막 등급 전이: 매우나쁨 → 건강 (회복)', gradeEvents.length > 0 && gradeEvents[gradeEvents.length - 1].payload.to === 'healthy');
  const chainOk = events.every((e, i) => i === 0 || e.payload.from === events[i - 1].payload.to);
  push('전이 체인 일관성 (from == 이전 to)', chainOk);
  const pestEvent = events.find((e) => e.payload.pest);
  push('해충 등급 전이 payload 동반 (별도 모델 결과)', !!pestEvent && !!pestEvent.payload.pest.levelKo);

  // 알림 센터 (폴링) 집계 + 식물명 조인
  const notis = await master.notifications();
  const healthNotis = notis.filter((n) => n.type === 'health_change');
  push('알림 센터 집계 (plant_name 조인)', healthNotis.length >= 3 && healthNotis.every((n) => n.plant_name.includes('심바질')));

  // 식물 최신 분석 — 마지막 상태(건강) 반영
  const latest = await master.latestAnalysis(plantId);
  push(`최신 분석 상태 = 건강 (실제 ${latest.analysis.healthGradeKo})`, latest.analysis.healthGrade === 'healthy');

  record('S3', '건강변화 알림 E2E', checks.every(Boolean), `${events.length}건 전이 / 체인 ${chainOk ? '정상' : '불량'}`);
}

// ─── S4: camera_mount 정책 기반 가로/세로 자동 촬영 (E2E) ───
async function scenarioS4(base) {
  log('\n[S4] 카메라 위치 기반 가로/세로 자동 촬영 (E2E — 실제 백엔드 연결)');
  const { master, farmer, world, creds, plantId } = await setupE2E(base, 's4');
  await master.subscribe(creds.slaveId);
  await farmer.start();
  await sleep(400);

  const cases = [
    { mount: 'auto', displayRotation: 1, expect: 'landscape', desc: 'auto + 기기 가로 설치' },
    { mount: 'auto', displayRotation: 0, expect: 'portrait', desc: 'auto + 기기 세로 설치' },
    { mount: 'landscape', displayRotation: 0, expect: 'landscape', desc: '가로 고정 설치' },
    { mount: 'portrait', displayRotation: 1, expect: 'portrait', desc: '세로 고정 설치' },
  ];
  const checks = [];
  for (const c of cases) {
    await master.setCameraMount(creds.slaveId, c.mount);
    world.displayRotation = c.displayRotation;
    await sleep(900); // 정책 동기화 대기
    const { event } = await master.requestPhoto(creds.slaveId, plantId);
    const photo = await master.downloadPhoto(event.url);
    const ok = photo.orientation === c.expect && photo.contentType.includes('image/png');
    checks.push(ok);
    log(`  ✓ ${c.desc} → ${photo.width}x${photo.height} (${photo.orientation}) ${ok ? '통과' : '실패'}`);
  }
  await farmer.stop();
  master.unsubscribe();
  record('S4', '자동 가로/세로 촬영 E2E', checks.every(Boolean), `${checks.filter(Boolean).length}/${checks.length} 케이스 통과`);
}

// ─── 메인 ───
async function main() {
  const args = parseArgs();
  log('╔══════════════════════════════════════════════════════════╗');
  log('║  OOJOO FARM — Farmer 기능 PC 시뮬레이션                  ║');
  log('║  Jev 건강판단 / 해충탐지 / 변화 알림 / 자동 방향 촬영      ║');
  log('╚══════════════════════════════════════════════════════════╝');
  const wanted = args.scenario === 'all' ? ['s1', 's2', 's3', 's4'] : args.scenario.split(',').map((s) => s.trim());
  const needsBackend = wanted.some((s) => s === 's3' || s === 's4');
  if (needsBackend) {
    try {
      await Api.health(args.base);
      log(`백엔드 연결 확인: ${args.base}`);
    } catch {
      log(`✗ 백엔드에 연결할 수 없습니다: ${args.base}`);
      log('  먼저 실행: backend 스타트 (scripts/start-backend.bat) 또는 simulation/scripts/run-sim.bat');
      process.exit(1);
    }
  }
  try {
    if (wanted.includes('s1')) await scenarioS1(args.n);
    if (wanted.includes('s2')) await scenarioS2(args.n);
    if (wanted.includes('s3')) await scenarioS3(args.base);
    if (wanted.includes('s4')) await scenarioS4(args.base);
  } catch (e) {
    log(`✗ 시나리오 오류: ${e.message}`);
    record('ERR', e.message, false, e.message);
  }

  log('\n──────── 결과 요약 ────────');
  for (const r of results) log(`  ${r.pass ? '✔' : '✗'} [${r.id}] ${r.name} — ${r.detail}`);
  const failed = results.filter((r) => !r.pass).length;
  log(failed === 0 ? '\n전체 시나리오 통과 — Android 이식 준비 완료' : `\n실패 ${failed}건 — 위 로그 확인 필요`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('시뮬레이션 러너 오류:', e);
  process.exit(1);
});
