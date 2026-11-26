@echo off
rem OOJOO 로컬 model-manager 실행 - http://127.0.0.1:4900 (처음 한 번 npm install)
cd /d "%~dp0..\ml\model-manager"
if not exist node_modules (call npm install --no-audit --no-fund)
start "" http://127.0.0.1:4900
node server.mjs
