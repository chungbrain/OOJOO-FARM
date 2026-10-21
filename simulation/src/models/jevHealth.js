// F1 — JevHealthNet (health_grade.bin) JS 런타임.
// 식물 건강등급 5단계(매우건강/건강/보통/나쁨/매우나쁨) 초경량 판단.
// Android assets 의 실제 모델 파일을 그대로 로드한다 (기기 = 시뮬레이터 동일 모델).
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseModel, forward, argmaxSoftmax } from './ojp1.js';

const MODEL_PATH = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../android/slave/app/src/main/assets/models/health_grade.bin'
);

export const MODEL_ID = 'health_grade';
export const LABELS = ['very_healthy', 'healthy', 'fair', 'poor', 'very_poor'];
export const LABEL_KO = {
  very_healthy: '매우건강',
  healthy: '건강',
  fair: '보통',
  poor: '나쁨',
  very_poor: '매우나쁨',
};

let cached = null;

export function loadModel() {
  if (!cached) cached = parseModel(readFileSync(MODEL_PATH));
  return cached;
}

/** rgb48: Float32Array(48*48*3) 0..1 → { grade, gradeKo, confidence, index, modelId } */
export function infer(rgb48) {
  const model = loadModel();
  const logits = forward(model, rgb48);
  const { index, confidence } = argmaxSoftmax(logits);
  const grade = LABELS[index] ?? 'healthy';
  return { grade, gradeKo: LABEL_KO[grade] ?? grade, confidence, index, modelId: MODEL_ID };
}
