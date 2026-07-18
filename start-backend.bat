@echo off
setlocal
set BACKEND_DIR=%~dp0backend
cd /d "%BACKEND_DIR%"

where node >nul 2>&1
if errorlevel 1 (
  for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\OpenJS.NodeJS.22*") do (
    if exist "%%D\node-v*\node.exe" (
      for /d %%N in ("%%D\node-v*") do set "PATH=%%N;%PATH%"
    )
  )
  if exist "C:\Program Files\nodejs\node.exe" set "PATH=C:\Program Files\nodejs;%PATH%"
)

where node >nul 2>&1
if errorlevel 1 (
  echo [OOJOO] Node.js not found. Install Node 22+ first.
  exit /b 1
)

if not exist "node_modules" (
  echo [OOJOO] npm install...
  call npm install
)

if not exist ".env" (
  echo PORT=4000> .env
  echo HOST=0.0.0.0>> .env
  echo DB_PATH=./data/oojoo.db>> .env
)

echo [OOJOO] Starting backend on 0.0.0.0:4000 ...
echo [OOJOO] Emulator connects via http://10.0.2.2:4000/
node --experimental-sqlite src/server.js
endlocal
