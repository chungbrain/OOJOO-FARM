// model-manager 성능 집계 — 검증(오프라인) 지표 외에 실제 농장에서의 판독 성능을 본다.
//  - 현장 신뢰도: farmer 가 보낸 판독 결과(detector_results)의 신뢰도 추이 — 버전(sha)별로도
//  - 사람 확인 일치율: 영상 건강진단에서 사람이 확정한 라벨과 AI 라벨의 일치 (종 모델)
//  - 새 사진 정확도: 배포 버전이 학습하지 않은(그 뒤에 추가된) 사진에서의 정확도 — '평가' 작업 결과
//  - 재학습 권장 판정
import db from '../db.js';
import {
  allModels, versionRows, activeVersion, labelsMeta, parseJson, speciesAliasMap, resolveSpecies,
} from './registry.js';
import { plantDetectorIds, detectorIds } from '../lib/detectors.js';
import { trainedUpTo } from './jobs.js';

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

export function sampleCounts(modelId) {
  const rows = db.prepare('SELECT label, split, COUNT(*) AS n FROM ml_samples WHERE model_id=? GROUP BY label, split').all(modelId);
  const out = {};
  for (const r of rows) {
    out[r.label] ??= { train: 0, val: 0 };
    out[r.label][r.split === 'val' ? 'val' : 'train'] += r.n;
  }
  return out;
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
  const ev = parseJson(active.eval_json, null);
  if (ev?.unseen && ev.unseen.n >= 10 && ev.unseen.accuracy < 0.7) {
    reasons.push({ level: 'warn', code: 'unseen_accuracy', accuracy: ev.unseen.accuracy, n: ev.unseen.n });
  }
  if (agreement && agreement.n >= 5 && agreement.agreement < 0.7) {
    reasons.push({ level: 'warn', code: 'human_disagreement', agreement: agreement.agreement, n: agreement.n });
  }
  const newSamples = db.prepare('SELECT COUNT(*) AS n FROM ml_samples WHERE model_id=? AND rowid > ?').get(model.id, trainedUpTo(active)).n;
  if (newSamples >= 10) reasons.push({ level: 'info', code: 'new_samples', n: newSamples });
  const activeLabels = parseJson(active.labels_json, []);
  const pending = labelsMeta(model).map((l) => l.id).filter((l) => !activeLabels.includes(l));
  if (pending.length) reasons.push({ level: 'info', code: 'new_labels', labels: pending });
  const status = reasons.some((r) => r.level === 'warn') ? 'warn' : reasons.length ? 'info' : 'ok';
  return { status, reasons };
}

export function allSummaries() {
  const ctx = usageContext();
  return allModels().map((m) => {
    const active = activeVersion(m.id);
    const field = fieldStats(m);
    const agreement = humanAgreement(m);
    const counts = sampleCounts(m.id);
    const metrics = parseJson(active?.metrics_json, null);
    const ev = parseJson(active?.eval_json, null);
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
      unseenAccuracy: ev?.unseen?.accuracy ?? null,
      field: { recent: field.recent, previous: field.previous },
      agreement,
      samples: Object.values(counts).reduce((a, c) => a + c.train + c.val, 0),
      usage: usage(m, ctx),
      health: health(m, field, agreement),
    };
  });
}
