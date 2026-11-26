// 앱 내장 모델 정의 (순수 데이터 — DB/파일 접근 없음).
// 서버 배포 레지스트리(registry.js)와 로컬 model-manager(ml/model-manager)가 같이 쓴다.

export const SPECIES_LABELS = [
  { id: 'healthy', ko: '건강', en: 'Healthy' },
  { id: 'water_low', ko: '물 부족', en: 'Underwatered' },
  { id: 'water_high', ko: '물 과다', en: 'Overwatered' },
  { id: 'light_low', ko: '햇빛 부족', en: 'Low light' },
  { id: 'pest', ko: '해충 발생', en: 'Pests' },
  { id: 'heat', ko: '온도 더움', en: 'Heat stress' },
];

// 종 id → [한글 이름, 영문 이름, 별칭] — farmer PlantHealthNet.ALIAS 와 같은 별칭
export const SPECIES = {
  basil: ['바질', 'Basil', ['바질', 'basil']],
  cactus: ['선인장', 'Cactus', ['선인장', 'cactus']],
  cherry_tomato: ['방울토마토', 'Cherry tomato', ['방울토마토', '토마토', 'tomato', 'cherrytomato', 'cherry tomato']],
  green_onion: ['대파', 'Green onion', ['대파', '파', 'greenonion', 'green onion']],
  herb: ['허브', 'Herb', ['허브', 'herb', '로즈마리', 'rosemary']],
  lettuce: ['상추', 'Lettuce', ['상추', 'lettuce']],
  onion: ['양파', 'Onion', ['양파', 'onion']],
  pepper: ['고추', 'Pepper', ['고추', 'chili', 'pepper']],
  perilla: ['깻잎', 'Perilla', ['깻잎', 'perilla', 'shiso']],
  potato: ['감자', 'Potato', ['감자', 'potato']],
  pumpkin: ['호박', 'Pumpkin', ['호박', 'pumpkin']],
  strawberry: ['딸기', 'Strawberry', ['딸기', 'strawberry']],
  sweet_potato: ['고구마', 'Sweet potato', ['고구마', 'sweetpotato', 'sweet potato']],
  zucchini: ['애호박', 'Zucchini', ['애호박', 'zucchini', 'squash']],
};

export const BUILTIN_DETECTORS = {
  health_grade: {
    icon: '🌿', name: '건강상태 판별', nameEn: 'Health check',
    description: '잎 색·밀도·시듦을 보고 건강등급을 5단계로 판별합니다. 등급이 바뀌면 알림을 보냅니다.',
    descriptionEn: 'Grades plant health in 5 levels from leaf color, density and wilting. Alerts when the grade changes.',
    labels: [
      { id: 'very_healthy', ko: '매우건강', en: 'Very healthy' },
      { id: 'healthy', ko: '건강', en: 'Healthy' },
      { id: 'fair', ko: '보통', en: 'Fair' },
      { id: 'poor', ko: '나쁨', en: 'Poor' },
      { id: 'very_poor', ko: '매우나쁨', en: 'Very poor' },
    ],
  },
  pest: {
    icon: '🐛', name: '해충 검출', nameEn: 'Pest detection',
    description: '식해 구멍·검은 반점으로 해충 발생 정도를 4단계로 판독합니다. 건강등급과 별개 모델입니다.',
    descriptionEn: 'Rates pest damage in 4 levels from bite holes and dark spots. Separate from the health model.',
    labels: [
      { id: 'none', ko: '해충없음', en: 'None' },
      { id: 'light', ko: '경미발생', en: 'Light' },
      { id: 'moderate', ko: '보통발생', en: 'Moderate' },
      { id: 'severe', ko: '심각발생', en: 'Severe' },
    ],
  },
};
export const BUILTIN_DETECTOR_IDS = Object.keys(BUILTIN_DETECTORS);

export const ID_RE = /^[a-z][a-z0-9_]{1,30}$/;

/** 앱 내장 모델 16개의 모델 정의 (id, kind, 이름, 라벨, 별칭) */
export function bundledModelDefs() {
  const out = [];
  for (const [id, d] of Object.entries(BUILTIN_DETECTORS)) {
    out.push({ id, kind: 'detector', icon: d.icon, name: d.name, nameEn: d.nameEn, description: d.description, descriptionEn: d.descriptionEn, labels: d.labels, aliases: [], baseModel: null });
  }
  for (const [id, [ko, en, aliases]] of Object.entries(SPECIES)) {
    out.push({
      id, kind: 'species', icon: '🌱', name: ko, nameEn: en,
      description: `${ko} 종별 진단 모델 — 관수 주기 모듈이 물 부족/과다 판독에 쓴다`,
      descriptionEn: `${en} diagnosis model — the watering module reads dry/wet from it`,
      labels: SPECIES_LABELS, aliases, baseModel: null,
    });
  }
  return out;
}

/** 학습 스크립트(ml/plant_health/metrics/<id>.json)의 합성 검증 지표 → 버전 metrics 형식 */
export function metricsFromConfusion(confusion, labels, extra = {}) {
  const perClass = labels.map((label, k) => {
    const row = confusion[k] || [];
    const support = row.reduce((a, b) => a + b, 0);
    const predicted = confusion.reduce((a, r) => a + (r[k] || 0), 0);
    const tp = row[k] || 0;
    const precision = predicted ? tp / predicted : null;
    const recall = support ? tp / support : null;
    const f1 = precision && recall ? (2 * precision * recall) / (precision + recall) : support ? 0 : null;
    return { label, support, precision, recall, f1 };
  });
  const scored = perClass.filter((c) => c.support > 0);
  const n = perClass.reduce((a, c) => a + c.support, 0);
  const correct = labels.reduce((a, _, k) => a + ((confusion[k] || [])[k] || 0), 0);
  return {
    n,
    accuracy: n ? correct / n : null,
    macroF1: scored.length ? scored.reduce((a, c) => a + c.f1, 0) / scored.length : null,
    perClass,
    confusion,
    labels,
    ...extra,
  };
}

export const normAlias = (s) => String(s ?? '').trim().toLowerCase().replace(/ /g, '');
