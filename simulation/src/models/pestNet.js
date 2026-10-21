// F2 — PestNet (pest.bin) JS 런타임.
// 해충발생 전용 4단계(해충없음/경미/보통/심각) 초경량 CNN —
// 건강등급 판단(JevHealthNet)과 완전히 별도 모델. Android assets 실제 파일 로드.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseModel, forward, argmaxSoftmax } from './ojp1.js';

const MODEL_PATH = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../android/slave/app/src/main/assets/models/pest.bin'
);

export const MODEL_ID = 'pest';
export const LABELS = ['none', 'light', 'moderate', 'severe'];
export const LABEL_KO = {
  none: '해충없음',
  light: '경미발생',
  moderate: '보통발생',
  severe: '심각발생',
};

let cached = null;

export function loadModel() {
  if (!cached) cached = parseModel(readFileSync(MODEL_PATH));
  return cached;
}

/** rgb48: Float32Array(48*48*3) 0..1 → { level, levelKo, confidence, index, modelId } */
export function infer(rgb48) {
  const model = loadModel();
  const logits = forward(model, rgb48);
  const { index, confidence } = argmaxSoftmax(logits);
  const level = LABELS[index] ?? 'none';
  return { level, levelKo: LABEL_KO[level] ?? level, confidence, index, modelId: MODEL_ID };
}
