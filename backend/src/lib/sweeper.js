import db from '../db.js';

// slaves.online 은 heartbeat/이벤트가 1 로만 올리고, 0 으로 내리는 코드가
// 전혀 없어 죽은 슬레이브가 영구 "온라인"으로 남았다.
// main loop(60s) 이 마지막 신호(last_seen) 이후 TTL 만큼 지난 대상을 오프라인 처리.
// heartbeat 는 30s 주기(slave 메인 루프) → 3분 = 6회 연속 누락 참음.
export const OFFLINE_TTL_MINUTES = 3;

/** 오프라인으로 전환한 slave 수 반환 (테스트/로그용). */
export function sweepOfflineSlaves() {
  const info = db.prepare(`
    UPDATE slaves SET online = 0
    WHERE online = 1
      AND last_seen IS NOT NULL
      AND last_seen < datetime('now', ?)
  `).run(`-${OFFLINE_TTL_MINUTES} minutes`);
  return Number(info.changes);
}
