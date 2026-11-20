#!/usr/bin/env bash
# OOJOO FARM — 앱 코드 변경 → 에뮬레이터 반영 (증분 빌드 + 설치 + 재시작).
#
#   1) gradle 증분 assembleDebug (master+slave)
#   2) adb install -r (양 APK)
#   3) 앱 프로세스 재시작
#   4) versionCode/lastUpdateTime 보고 (동기 증빙)
#
#   ./scripts/emulator/sync-apps.sh
set -euo pipefail
source "$(dirname "$0")/emulator.env"
mkdir -p "$RUN_DIR"

log "=== sync-apps ==="

if ! device_ready; then
  log "ERROR: emulator not in 'device' state (emulator-up.sh 먼저 실행)"
  exit 1
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$JAVA_HOME_FOR_BUILD"
cd "$ANDROID_DIR"
log "gradle assembleDebug (master+slave) ..."
./gradlew :master:app:assembleDebug :slave:app:assembleDebug --console=plain

log "installing APKs ..."
"$ADB" install -r "$MASTER_APK"
"$ADB" install -r "$SLAVE_APK"

log "restarting apps ..."
"$ADB" shell am force-stop "$MASTER_PKG"
"$ADB" shell am force-stop "$SLAVE_PKG"
sleep 2
# 마스터가 먼저, 슬레이브는 5초 후 — FGS 를 가진 슬레이브가 마지막에
# 기동하면 화면 맨 위(포그라운드)에 남는다. 슬레이브 대시보드의
# startFromUi(일반 startService, 10초 FGS 윈도우 없음)가 포그라운드
# 요건만 보면 되므로 이게 TCG 콜드 스타트에서 가장 안전한 순서다.
"$ADB" shell am start -n "$MASTER_PKG/.MainActivity" >/dev/null
sleep 5
"$ADB" shell am start -n "$SLAVE_PKG/.MainActivity" >/dev/null

log "master: $(pkg_info "$MASTER_PKG")"
log "slave:  $(pkg_info "$SLAVE_PKG")"
log "sync-apps done"
