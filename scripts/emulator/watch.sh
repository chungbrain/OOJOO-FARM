#!/usr/bin/env bash
# OOJOO FARM — 에뮬레이터 자동반영 워처 (폴링).
#
#   android/ 소스 변경 → sync-apps.sh (증분 빌드 + 설치 + 앱 재시작)
#   backend/  소스 변경 → 백엔드 재기동 (:4000)
#
# inotify 없음 (호스트) → mtime 스냅샷 폴링. pidfile 단일 인스턴스,
# SYNC_COOLDOWN_SEC 동안의 연속 변경은 1회로 댐핑.
#
#   ./scripts/emulator/watch.sh                 # 전방 실행
#   nohup ./scripts/emulator/watch.sh >> .dev-run/emulator-watch.log 2>&1 &
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/emulator.env"
mkdir -p "$RUN_DIR"

# 단일 인스턴스 가드
if [ -f "$WATCH_PIDFILE" ]; then
  oldpid=$(cat "$WATCH_PIDFILE" 2>/dev/null || echo "")
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    log "watcher already running (pid $oldpid) — nothing to do"
    exit 0
  fi
fi
echo $$ > "$WATCH_PIDFILE"
trap 'rm -f "$WATCH_PIDFILE"' EXIT INT TERM

# watched 파일들의 mtime 스냅샷 해시 (변경 감지용)
tree_state() {
  local dir="$1"
  find "$dir" \
    \( -name build -o -name .gradle -o -name node_modules -o -name dist -o -name data \) -prune -o \
    -type f \( -name "*.kt" -o -name "*.kts" -o -name "*.xml" -o -name "*.js" -o -name "*.mjs" \
               -o -name "*.json" -o -name "*.html" -o -name "*.properties" \) \
    ! -name "local.properties" -print0 2>/dev/null \
    | xargs -0 stat -c "%Y %n" 2>/dev/null | sort | md5sum | cut -d' ' -f1
}

log "watcher started (pid $$, poll ${POLL_INTERVAL_SEC}s, cooldown ${SYNC_COOLDOWN_SEC}s)"
state_a=$(tree_state "$ANDROID_DIR")
state_b=$(tree_state "$BACKEND_DIR")
cur_a=$state_a
cur_b=$state_b
pending_a=0
pending_b=0
last_sync=$(date +%s)

while true; do
  sleep "$POLL_INTERVAL_SEC"
  cur_a=$(tree_state "$ANDROID_DIR")
  cur_b=$(tree_state "$BACKEND_DIR")
  [ "$cur_a" != "$state_a" ] && pending_a=1
  [ "$cur_b" != "$state_b" ] && pending_b=1

  now=$(date +%s)
  if [ "$pending_b" = 1 ] && [ $((now - last_sync)) -ge "$SYNC_COOLDOWN_SEC" ]; then
    pending_b=0
    state_b=$cur_b
    last_sync=$now
    log "backend/ changed -> restarting"
    if backend_restart; then
      log "backend restarted OK"
    else
      log "ERROR: backend restart failed (see $BACKEND_LOG)"
    fi
  fi
  if [ "$pending_a" = 1 ] && [ $((now - last_sync)) -ge "$SYNC_COOLDOWN_SEC" ]; then
    pending_a=0
    state_a=$cur_a
    last_sync=$now
    log "android/ changed -> sync-apps"
    if "$SCRIPT_DIR/sync-apps.sh"; then
      log "sync-apps OK"
    else
      log "ERROR: sync-apps failed (다음 감지 시 재시도)"
    fi
  fi
done
