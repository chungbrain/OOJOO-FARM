#!/usr/bin/env bash
# OOJOO FARM — 에뮬레이터 기동 (멱등).
#   1) 백엔드 :4000 기동 (이미 뜨 있으면 그대로)
#   2) AVD 기동 (KVM 없는 호스트 → TCG 강제, 첫 부팅 10~30분) + 부팅 완료 대기
#   3) 위험 권한 사전 위임 + 화면 깨우기
#
#   ./scripts/emulator/emulator-up.sh
set -euo pipefail
source "$(dirname "$0")/emulator.env"
mkdir -p "$RUN_DIR"

log "=== emulator-up ==="

# 1) 백엔드
if health_ok; then
  log "backend already running on :$PORT"
else
  log "starting backend on :$PORT"
  if ! backend_restart; then
    log "ERROR: backend did not become healthy (see $BACKEND_LOG)"
    exit 1
  fi
  log "backend up"
fi

# 2) 에뮬레이터
if device_ready && boot_completed; then
  log "emulator already booted"
else
  if pgrep -f "emulator -avd $AVD_NAME" >/dev/null 2>&1; then
    log "emulator process running — waiting for boot"
  else
    log "starting emulator '$AVD_NAME' (TCG — first boot can take 10-30 min)"
    ANDROID_I_WANT_MY_TCG=yes nohup "$EMULATOR_BIN" -avd "$AVD_NAME" \
      -accel "$EMU_ACCEL" "${EMU_EXTRA_FLAGS[@]}" >> "$EMULATOR_LOG" 2>&1 &
    echo $! > "$EMULATOR_PIDFILE"
  fi
  "$ADB" wait-for-device
  # TCG 부팅: 최대 2시간 대기 (5초 폴링)
  for i in $(seq 1 1440); do
    if boot_completed; then break; fi
    sleep 5
  done
  if ! boot_completed; then
    log "ERROR: boot not completed (see $EMULATOR_LOG)"
    exit 1
  fi
  log "boot completed"
fi

# 3) 권한 + 화면
grant_permissions
"$ADB" shell input keyevent KEYCODE_WAKEUP
log "done — app server URL: $SERVER_URL"
log "monitoring: http://127.0.0.1:$PORT/admin"
