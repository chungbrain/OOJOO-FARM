// 모델 현장 성능 집계 (서버 쪽) — 로컬 model-manager 가 /api/models 로 받아 성능 화면에 보여준다.
//  - 현장 신뢰도: farmer 가 보낸 판독 결과(detector_results)의 신뢰도 추이 — 버전(sha)별로도
//  - 사람 확인 일치율: 영상 건강진단에서 사람이 확정한 라벨과 AI 라벨의 일치 (종 모델)
//  - 재학습 권장 판정 중 현장 신호 (새 사진 정확도·새 라벨 등 학습 데이터 쪽 판정은 로컬에서)
import db from '../db.js';
import {
  allModels, versionRows, activeVersion, labelsMeta, parseJson, speciesAliasMap, resolveSpecies,
} from './registry.js';
import { plantDetectorIds, detectorIds } from '../lib/detectors.js';

export const LOW_CONFIDENCE = 0.6;
const MIN_FIELD = 20;

/** 이 모델의 판독 결과를 고르는 조건 — 종 모델은 관수 모듈 결과 중 그 종 모델로 판독한 것 */
function resultFilter(model) {
  return model.kind === 'species'
    ? { sql: "module_id='watering' AND json_extract(detail_json, '$.source') = ?", arg: `species:${model.id}` }
    : { sql: 'module_id = ?', arg: model.id };
}

function window(model, from, to) {
  const f = resultFilter(model);
  const r = db.prepare(`SELECT COUNT(*) AS n, AVG(confidence) AS conf,
      SUM(CASE WHEN confidence < ${LOW_CONFIDENCE} THEN 1 ELSE 0 END) AS low
    FROM detector_results WHERE ${f.sql} AND created_at >= datetime('now', ?) AND created_at < datetime('now', ?)`)
    .get(f.arg, from, to);
  return { n: r.n, avgConfidence: r.conf, lowRate: r.n ? r.low / r.n : null };
}

export function fieldStats(model) {
  const f = resultFilter(model);
  const daily = db.prepare(`SELECT date(created_at) AS day, COUNT(*) AS n, AVG(confidence) AS conf,
      SUM(CASE WHEN confidence < ${LOW_CONFIDENCE} THEN 1 ELSE 0 END) AS low
    FROM detector_results WHERE ${f.sql} AND created_at >= datetime('now', '-30 days')
    GROUP BY day ORDER BY day`).all(f.arg)
    .map((r) => ({ day: r.day, n: r.n, avgConfidence: r.conf, lowRate: r.n ? r.low / r.n : null }));
  const shaToVersion = new Map(versionRows(model.id).map((v) => [v.sha256, v.version]));
  const byVersion = db.prepare(`SELECT model_sha AS sha, COUNT(*) AS n, AVG(confidence) AS conf,
      SUM(CASE WHEN confidence < ${LOW_CONFIDENCE} THEN 1 ELSE 0 END) AS low, MAX(created_at) AS last
    FROM detector_results WHERE ${f.sql} GROUP BY model_sha`).all(f.arg)
    .map((r) => ({
      version: shaToVersion.get(r.sha) ?? null, // null = 내장 모델 폴백(sha 없음) 또는 모르는 파일
      n: r.n, avgConfidence: r.conf, lowRate: r.n ? r.low / r.n : null, last: r.last,
    }));
  const labels = db.prepare(`SELECT label, COUNT(*) AS n FROM detector_results
    WHERE ${f.sql} AND created_at >= datetime('now', '-7 days') GROUP BY label ORDER BY n DESC`).all(f.arg);
  return {
    lowThreshold: LOW_CONFIDENCE,
    recent: window(model, '-7 days', '+1 second'),
    previous: window(model, '-14 days', '-7 days'),
    daily,
    byVersion,
    labels,
  };
}

/** 영상 건강진단에서 사람이 확정한 라벨 vs AI 라벨 (종 모델만) */
export function humanAgreement(model) {
  if (model.kind !== 'species') return null;
  let rows = [];
  try {
    rows = db.prepare(`SELECT ai_label, human_label FROM diagnosis_sessions
      WHERE human_label IS NOT NULL AND model_id = ? AND created_at >= datetime('now', '-90 days')`).all(model.id);
  } catch { return null; } // 진단 테이블이 아직 없는 DB
  if (!rows.length) return { n: 0, agreement: null };
  const same = rows.filter((r) => r.ai_label === r.human_label).length;
  return { n: rows.length, agreement: same / rows.length };
}

/** 이 모델을 쓰는 식물 수 — 검출 모듈은 모듈이 켜진 식물, 종 모델은 관수 모듈이 켜진 그 종 식물 */
export function usage(model, ctx = usageContext()) {
  if (model.kind === 'species') {
    return ctx.plants.filter((p) => p.detectors.includes('watering') && resolveSpecies(p.species ?? p.name, ctx.aliases) === model.id).length;
  }
  return ctx.plants.filter((p) => p.detectors.includes(model.id)).length;
}

export function usageContext() {
  const ids = detectorIds();
  const plants = db.prepare('SELECT id, name, species, detectors_json FROM plants').all()
    .map((p) => ({ ...p, detectors: plantDetectorIds(p, ids) }));
  return { plants, aliases: speciesAliasMap() };
}

/** 모델이 없는 식물 종 — 새 종 모델을 만들 후보 */
export function unmatchedSpecies(ctx = usageContext()) {
  const counts = new Map();
  for (const p of ctx.plants) {
    const raw = (p.species ?? p.name ?? '').trim();
    if (!raw || resolveSpecies(raw, ctx.aliases)) continue;
    counts.set(raw, (counts.get(raw) || 0) + 1);
  }
  return [...counts.entries()].map(([raw, plants]) => ({ raw, plants })).sort((a, b) => b.plants - a.plants);
}

/** 재학습 권장 판정 — level: warn(성능 저하 신호) / info(재학습하면 좋아질 여지) */
export function health(model, field = fieldStats(model), agreement = humanAgreement(model)) {
  const active = activeVersion(model.id);
  if (!active) return { status: 'untrained', reasons: [{ level: 'info', code: 'no_version' }] };
  const reasons = [];
  const { recent, previous } = field;
  if (recent.n >= MIN_FIELD && previous.n >= MIN_FIELD && recent.avgConfidence < previous.avgConfidence - 0.1) {
    reasons.push({ level: 'warn', code: 'confidence_drop', from: previous.avgConfidence, to: recent.avgConfidence });
  }
  if (recent.n >= MIN_FIELD && recent.lowRate > 0.3) {
    reasons.push({ level: 'warn', code: 'low_confidence', rate: recent.lowRate });
  }
  if (agreement && agreement.n >= 5 && agreement.agreement < 0.7) {
    reasons.push({ level: 'warn', code: 'human_disagreement', agreement: agreement.agreement, n: agreement.n });
  }
  const status = reasons.some((r) => r.level === 'warn') ? 'warn' : reasons.length ? 'info' : 'ok';
  return { status, reasons };
}

export function allSummaries() {
  const ctx = usageContext();
  return allModels().map((m) => {
    const active = activeVersion(m.id);
    const field = fieldStats(m);
    const agreement = humanAgreement(m);
    const metrics = parseJson(active?.metrics_json, null);
    return {
      id: m.id,
      kind: m.kind,
      icon: m.icon,
      name: m.name,
      nameEn: m.name_en,
      labels: labelsMeta(m),
      activeVersion: active?.version ?? null,
      activeSource: active?.source ?? null,
      accuracy: metrics?.accuracy ?? null,
      macroF1: metrics?.macroF1 ?? null,
      metricsDataset: metrics?.dataset ?? null,
      field: { recent: field.recent, previous: field.previous },
      agreement,
      usage: usage(m, ctx),
      health: health(m, field, agreement),
    };
  });
}
