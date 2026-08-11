#!/usr/bin/env bash
# OOJOO FARM backend launcher for Ubuntu (corp network / MITM proxy)
# Linux equivalent of start-backend.bat
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$BACKEND_DIR"

# --- Toolchain: portable Node 22 (node:sqlite requires Node 22+) ---
# Override with: NODE_HOME=/path/to/node22 ./run-backend.sh
NODE_HOME="${NODE_HOME:-/home/chungki/.local/oojoo-task1/node-v22}"
export PATH="$NODE_HOME/bin:$PATH"

# --- Corp TLS-intercepting proxy: make Node trust the OS CA store ---
# (Samsung Digital City proxy re-signs TLS; system store has the root CA.)
export NODE_OPTIONS="--use-system-ca"

# --- Optional port override: PORT=4001 ./run-backend.sh ---
if [ "${PORT:-}" != "" ]; then
  export PORT
fi

echo "[OOJOO] node $(node -v) / npm $(npm -v)"

# --- .env ---
if [ ! -f .env ]; then
  echo "[OOJOO] creating .env from .env.example"
  cp .env.example .env
fi

# --- dependencies (npm registry only reachable via corp proxy) ---
if [ ! -d node_modules ]; then
  echo "[OOJOO] installing dependencies via corp proxy ..."
  http_proxy="http://168.219.61.252:8080/" \
  https_proxy="http://168.219.61.252:8080/" \
    npm install
fi

# Display the actual bind values from .env (server reads .env via dotenv)
BIND_HOST="$(grep -E '^HOST=' .env | cut -d= -f2)"; BIND_HOST="${BIND_HOST:-0.0.0.0}"
BIND_PORT="$(grep -E '^PORT=' .env | cut -d= -f2)"; BIND_PORT="${BIND_PORT:-4000}"
echo "[OOJOO] starting backend on ${BIND_HOST}:${BIND_PORT} (npm start) ..."
exec npm start
