#!/usr/bin/env bash
# OOJOO FARM — APK 빌드 → dist/ (고정 이름, 매 빌드 덮어쓰기)
#
#   sh scripts/build-dist.sh             # 빌드 + dist 복사
#   sh scripts/build-dist.sh --commit    # + git commit (build: debug APKs <태그>)
#
# dist/ 고정 이름 (기존 관례 — 빌드 커밋마다 갱신):
#   dist/oojoo-farm-master-debug.apk
#   dist/oojoo-farm-farmer-debug.apk    (slave = farmer 앱)
#
# 태그는 KST 날짜 + 당일 시퀀스 (예: 2026112504).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
DIST_DIR="$REPO_ROOT/dist"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-amazon-corretto}"

MASTER_APK="$ANDROID_DIR/master/app/build/outputs/apk/debug/app-debug.apk"
SLAVE_APK="$ANDROID_DIR/slave/app/build/outputs/apk/debug/app-debug.apk"
DIST_MASTER="$DIST_DIR/oojoo-farm-master-debug.apk"
DIST_SLAVE="$DIST_DIR/oojoo-farm-farmer-debug.apk"

log() { echo "[$(date +%H:%M:%S)] $*"; }

log "=== build-dist ==="
cd "$ANDROID_DIR"
log "gradle assembleDebug (master+slave) ..."
sh gradlew :master:app:assembleDebug :slave:app:assembleDebug --console=plain

mkdir -p "$DIST_DIR"
cp -f "$MASTER_APK" "$DIST_MASTER"
cp -f "$SLAVE_APK" "$DIST_SLAVE"

log "master: $(stat -c %s "$DIST_MASTER") bytes -> $DIST_MASTER"
log "slave:  $(stat -c %s "$DIST_SLAVE") bytes -> $DIST_SLAVE"
log "done"

if [ "${1:-}" = "--commit" ]; then
  today="$(TZ=Asia/Seoul date +%Y%m%d)"
  count=$(git -C "$REPO_ROOT" log --oneline | grep -c "build: debug APKs $today" || true)
  tag="${today}$(printf '%02d' $((count + 1)))"
  git -C "$REPO_ROOT" add dist/
  git -C "$REPO_ROOT" commit -m "build: debug APKs $tag"
  log "committed: $tag"
fi
