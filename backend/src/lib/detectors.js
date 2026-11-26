// 온디바이스 상태검출 모듈 카탈로그 — 서버가 단일 원천.
//  - master: 식물 카드에서 이 목록을 보고 식물별로 모듈을 추가/삭제한다.
//  - farmer: 식물에 켜진 모듈의 모델 파일(OJP1)을 이 서버에서 내려받아(sha256 검증)
//            ROI 크롭을 주기적·이벤트 시 판독하고 결과를 /api/detectors/results 로 돌려준다.
// 모델 파일은 backend/models/*.bin (android/slave assets 와 같은 OJP1 포맷).
// 학습 스크립트가 assets 를 갱신하면 scripts/sync-detector-models.mjs 로 복사한다.
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const MODELS_DIR = process.env.DETECTOR_MODELS_DIR || path.resolve(HERE, '../../models');

// 종별 6클래스 진단 모델(PlantHealthNet)이 있는 종 — 관수 주기 모듈이 종에 맞는 모델을 쓴다.
export const SPECIES_IDS = [
  'basil', 'cactus', 'cherry_tomato', 'green_onion', 'herb', 'lettuce', 'onion',
  'pepper', 'perilla', 'potato', 'pumpkin', 'strawberry', 'sweet_potato', 'zucchini',
];
const SPECIES_OUTPUTS = ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat'];

// triggers — farmer 가 판독을 돌리는 계기.
//  periodic: ROI 진단 주기(정책 roi_interval)마다
//  module_added: 모듈 추가 직후(모델 준비되면 즉시 1회)
//  run_now: master 의 '지금 판독' 명령
//  watered: 해당 식물 관수 직후(+ 후속 재확인)
//  pest_response: Fan/Laser 퇴치 직후 재확인
//  health_drop: 건강등급이 나빠지는 전이가 확정됐을 때 원인 확인
const CATALOG = [
  {
    id: 'health_grade',
    icon: '🌿',
    name: { ko: '건강상태 판별', en: 'Health check' },
    description: {
      ko: '잎 색·밀도·시듦을 보고 건강등급을 5단계로 판별합니다. 등급이 바뀌면 알림을 보냅니다.',
      en: 'Grades plant health in 5 levels from leaf color, density and wilting. Alerts when the grade changes.',
    },
    labels: ['very_healthy', 'healthy', 'fair', 'poor', 'very_poor'],
    labelsKo: ['매우건강', '건강', '보통', '나쁨', '매우나쁨'],
    labelsEn: ['Very healthy', 'Healthy', 'Fair', 'Poor', 'Very poor'],
    model: { file: 'health_grade.bin' },
    triggers: ['periodic', 'module_added', 'run_now', 'watered'],
  },
  {
    id: 'pest',
    icon: '🐛',
    name: { ko: '해충 검출', en: 'Pest detection' },
    description: {
      ko: '식해 구멍·검은 반점으로 해충 발생 정도를 4단계로 판독합니다. 건강등급과 별개 모델입니다.',
      en: 'Rates pest damage in 4 levels from bite holes and dark spots. Separate from the health model.',
    },
    labels: ['none', 'light', 'moderate', 'severe'],
    labelsKo: ['해충없음', '경미발생', '보통발생', '심각발생'],
    labelsEn: ['None', 'Light', 'Moderate', 'Severe'],
    model: { file: 'pest.bin' },
    triggers: ['periodic', 'module_added', 'run_now', 'pest_response', 'health_drop'],
  },
  {
    id: 'watering',
    icon: '💧',
    name: { ko: '관수 주기 모니터링', en: 'Watering cycle' },
    description: {
      ko: '식물 종에 맞는 모델로 물 부족·과다를 판독하고, 관수 후 다시 마르기까지의 주기를 추적합니다.',
      en: 'Reads dry/overwatered state with a species model and tracks how long the plant takes to dry out after watering.',
    },
    labels: ['dry', 'ok', 'wet'],
    labelsKo: ['물 부족', '적정', '물 과다'],
    labelsEn: ['Dry', 'OK', 'Overwatered'],
    // 종별 모델 — 출력 6클래스 중 water_low/water_high 를 dry/wet 으로 해석
    model: { perSpecies: true, outputs: SPECIES_OUTPUTS },
    triggers: ['periodic', 'module_added', 'run_now', 'watered', 'health_drop'],
  },
];

export const DETECTOR_IDS = CATALOG.map((m) => m.id);
// detectors_json 이 NULL 인 식물(이 기능 이전 식물/신규 식물)의 기본값 — 기존 동작(건강·해충·물 감시 상시)과 동일
export const DEFAULT_DETECTORS = ['health_grade', 'pest', 'watering'];

const hashCache = new Map(); // file -> { mtimeMs, size, sha256 }

/** 모델 파일 메타 (sha256 은 mtime 기준 캐시). 파일 없으면 null. */
export function modelFileInfo(file) {
  if (!/^[a-z0-9_]+\.bin$/.test(file)) return null;
  const full = path.join(MODELS_DIR, file);
  let st;
  try { st = fs.statSync(full); } catch { return null; }
  if (!st.isFile()) return null;
  const hit = hashCache.get(file);
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return { file, size: hit.size, sha256: hit.sha256, path: full };
  const sha256 = createHash('sha256').update(fs.readFileSync(full)).digest('hex');
  hashCache.set(file, { mtimeMs: st.mtimeMs, size: st.size, sha256 });
  return { file, size: st.size, sha256, path: full };
}

const modelRef = (info) => info && ({
  file: info.file,
  url: `/api/detectors/models/${info.file}`,
  sha256: info.sha256,
  size: info.size,
});

/** 카탈로그(모델 파일 해시·크기 포함) — farmer 다운로드/검증과 master 목록 공용. */
export function catalog() {
  return CATALOG.map((m) => {
    const out = {
      id: m.id,
      icon: m.icon,
      name: m.name.ko,
      nameEn: m.name.en,
      description: m.description.ko,
      descriptionEn: m.description.en,
      labels: m.labels,
      labelsKo: m.labelsKo,
      labelsEn: m.labelsEn,
      triggers: m.triggers,
      inputSize: 48,
      perSpecies: Boolean(m.model.perSpecies),
      model: null,
      speciesModels: null,
      speciesOutputs: null,
    };
    if (m.model.perSpecies) {
      const map = {};
      for (const s of SPECIES_IDS) {
        const ref = modelRef(modelFileInfo(`${s}.bin`));
        if (ref) map[s] = ref;
      }
      out.speciesModels = map;
      out.speciesOutputs = m.model.outputs;
      out.available = Object.keys(map).length > 0;
    } else {
      out.model = modelRef(modelFileInfo(m.model.file));
      out.available = out.model !== null;
    }
    return out;
  });
}

/** 다운로드 허용 파일 — 카탈로그가 참조하는 파일만 (경로 조작 차단). */
export function servableModelFile(file) {
  const allowed = new Set(CATALOG.flatMap((m) => (m.model.perSpecies ? SPECIES_IDS.map((s) => `${s}.bin`) : [m.model.file])));
  return allowed.has(file) ? modelFileInfo(file) : null;
}

export function catalogEntry(id) {
  return catalog().find((m) => m.id === id) || null;
}

export function labelKoOf(moduleId, label) {
  const m = CATALOG.find((x) => x.id === moduleId);
  const i = m ? m.labels.indexOf(label) : -1;
  return i >= 0 ? m.labelsKo[i] : null;
}

/** plants.detectors_json → [{id, addedAt}] (NULL = 기본 세트, 모르는 id 제거). */
export function plantDetectors(row) {
  if (!row || row.detectors_json == null) return DEFAULT_DETECTORS.map((id) => ({ id, addedAt: null }));
  try {
    const arr = JSON.parse(row.detectors_json);
    if (!Array.isArray(arr)) throw new Error('not array');
    const seen = new Set();
    return arr
      .map((e) => (typeof e === 'string' ? { id: e, addedAt: null } : { id: e?.id, addedAt: e?.addedAt ?? null }))
      .filter((e) => DETECTOR_IDS.includes(e.id) && !seen.has(e.id) && seen.add(e.id));
  } catch {
    return DEFAULT_DETECTORS.map((id) => ({ id, addedAt: null }));
  }
}

export function plantDetectorIds(row) {
  return plantDetectors(row).map((e) => e.id);
}
