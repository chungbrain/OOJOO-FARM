// 온디바이스 상태검출 모듈 카탈로그 — 서버가 단일 원천.
//  - master: 식물 카드에서 이 목록을 보고 식물별로 모듈을 추가/삭제한다.
//  - farmer: 식물에 켜진 모듈의 모델 파일(OJP1)을 이 서버에서 내려받아(sha256 검증)
//            ROI 크롭을 주기적·이벤트 시 판독하고 결과를 /api/detectors/results 로 돌려준다.
// 어떤 모델 파일을 내려줄지는 model-manager 레지스트리(src/ml/registry.js)의 배포(active) 버전이 정한다
// — model-manager 에서 새 버전을 배포하면 sha256 이 바뀌어 farmer 가 다음 동기화 때 새로 받는다.
import {
  allModels, activeVersion, versionRef, labelsMeta, fileByName, speciesAliasMap, parseJson,
  BUILTIN_DETECTOR_IDS,
} from '../ml/registry.js';

// 관수 주기 모듈이 읽는 종별 모델의 기본 출력 순서 (버전별 실제 순서는 speciesModels[*].labels)
const SPECIES_OUTPUTS = ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat'];

// triggers — farmer 가 판독을 돌리는 계기.
//  periodic: ROI 진단 주기(정책 roi_interval)마다
//  module_added: 모듈 추가 직후(모델 준비되면 즉시 1회)
//  run_now: master 의 '지금 판독' 명령
//  watered: 해당 식물 관수 직후(+ 후속 재확인)
//  pest_response: Fan/Laser 퇴치 직후 재확인
//  health_drop: 건강등급이 나빠지는 전이가 확정됐을 때 원인 확인
const TRIGGERS = {
  health_grade: ['periodic', 'module_added', 'run_now', 'watered'],
  pest: ['periodic', 'module_added', 'run_now', 'pest_response', 'health_drop'],
  watering: ['periodic', 'module_added', 'run_now', 'watered', 'health_drop'],
  custom: ['periodic', 'module_added', 'run_now'],
};

const WATERING = {
  id: 'watering',
  icon: '💧',
  name: '관수 주기 모니터링',
  nameEn: 'Watering cycle',
  description: '식물 종에 맞는 모델로 물 부족·과다를 판독하고, 관수 후 다시 마르기까지의 주기를 추적합니다.',
  descriptionEn: 'Reads dry/wet state with a species model and tracks how long the plant takes to dry out after watering.',
  labels: [
    { id: 'dry', ko: '물 부족', en: 'Dry' },
    { id: 'ok', ko: '적정', en: 'OK' },
    { id: 'wet', ko: '물 과다', en: 'Overwatered' },
  ],
};

// detectors_json 이 NULL 인 식물(이 기능 이전 식물/신규 식물)의 기본값 — 기존 동작(건강·해충·물 감시 상시)과 동일
export const DEFAULT_DETECTORS = ['health_grade', 'pest', 'watering'];

/** 배포 버전 라벨 순서대로 이름 붙이기 (모델에 아직 학습 안 된 새 라벨은 포함하지 않는다). */
function labelSet(model, ids) {
  const meta = new Map(labelsMeta(model).map((l) => [l.id, l]));
  return {
    labels: ids,
    labelsKo: ids.map((id) => meta.get(id)?.ko || id),
    labelsEn: ids.map((id) => meta.get(id)?.en || id),
  };
}

function detectorModule(model) {
  const v = activeVersion(model.id);
  const ref = v ? versionRef(v) : null;
  return {
    id: model.id,
    icon: model.icon || '🧠',
    name: model.name || model.id,
    nameEn: model.name_en || model.name || model.id,
    description: model.description || '',
    descriptionEn: model.description_en || model.description || '',
    ...labelSet(model, ref ? ref.labels : labelsMeta(model).map((l) => l.id)),
    triggers: TRIGGERS[model.id] || TRIGGERS.custom,
    inputSize: 48,
    perSpecies: false,
    model: ref,
    speciesModels: null,
    speciesOutputs: null,
    speciesAliases: null,
    available: ref !== null,
    builtin: BUILTIN_DETECTOR_IDS.includes(model.id),
  };
}

function wateringModule(models) {
  const speciesModels = {};
  for (const m of models.filter((x) => x.kind === 'species')) {
    const v = activeVersion(m.id);
    if (v) speciesModels[m.id] = versionRef(v);
  }
  return {
    id: WATERING.id,
    icon: WATERING.icon,
    name: WATERING.name,
    nameEn: WATERING.nameEn,
    description: WATERING.description,
    descriptionEn: WATERING.descriptionEn,
    labels: WATERING.labels.map((l) => l.id),
    labelsKo: WATERING.labels.map((l) => l.ko),
    labelsEn: WATERING.labels.map((l) => l.en),
    triggers: TRIGGERS.watering,
    inputSize: 48,
    perSpecies: true,
    model: null,
    speciesModels,
    speciesOutputs: SPECIES_OUTPUTS,
    // 식물 종 이름(정규화: 소문자·공백 제거) → 종 모델 id — model-manager 에서 만든 새 종도 여기로 찾는다
    speciesAliases: speciesAliasMap(),
    available: Object.keys(speciesModels).length > 0,
    builtin: true,
  };
}

/** 카탈로그(모델 파일 해시·크기 포함) — farmer 다운로드/검증과 master 목록 공용.
 *  순서: 건강상태 → 해충 → 관수 → model-manager 에서 만든 검출 모듈(배포 버전이 있는 것만). */
export function catalog() {
  const models = allModels();
  const byId = new Map(models.map((m) => [m.id, m]));
  const out = [];
  for (const id of BUILTIN_DETECTOR_IDS) if (byId.has(id)) out.push(detectorModule(byId.get(id)));
  out.push(wateringModule(models));
  for (const m of models) {
    if (m.kind === 'detector' && !BUILTIN_DETECTOR_IDS.includes(m.id) && m.active_version != null) out.push(detectorModule(m));
  }
  return out;
}

export function detectorIds() {
  return catalog().map((m) => m.id);
}

/** 다운로드 허용 파일 — 레지스트리에 등록된 모델 버전 파일만 (경로 조작 차단). */
export function servableModelFile(file) {
  return fileByName(file);
}

export function catalogEntry(id) {
  return catalog().find((m) => m.id === id) || null;
}

/** plants.detectors_json → [{id, addedAt}] (NULL = 기본 세트, 모르는 id 제거). */
export function plantDetectors(row, ids = detectorIds()) {
  const known = new Set(ids);
  if (!row || row.detectors_json == null) return DEFAULT_DETECTORS.filter((id) => known.has(id)).map((id) => ({ id, addedAt: null }));
  const arr = parseJson(row.detectors_json, null);
  if (!Array.isArray(arr)) return DEFAULT_DETECTORS.map((id) => ({ id, addedAt: null }));
  const seen = new Set();
  return arr
    .map((e) => (typeof e === 'string' ? { id: e, addedAt: null } : { id: e?.id, addedAt: e?.addedAt ?? null }))
    .filter((e) => known.has(e.id) && !seen.has(e.id) && seen.add(e.id));
}

export function plantDetectorIds(row, ids) {
  return plantDetectors(row, ids).map((e) => e.id);
}
