@echo off
REM OOJOO FARM — Farmer 기능 PC 시뮬레이션 러너
REM 백엔드(:4000) 기동을 확인한 뒤 시뮬레이션 시나리오(S1~S4)를 실행한다.
REM 사용: simulation\scripts\run-sim.bat [--scenario s1,s2] [--n 50]
setlocal

set SIM_DIR=%~dp0..
set ROOT=%~dp0..\..
set BASE=http://127.0.0.1:4000

where node >nul 2>&1
if errorlevel 1 (
  for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\OpenJS.NodeJS.22*") do (
    if exist "%%D\node-v*\node.exe" (
      for /d %%N in ("%%D\node-v*") do set "PATH=%%N;%%PATH%"
    )
  )
  if exist "C:\Program Files\nodejs\node.exe" set "PATH=C:\Program Files\nodejs;%PATH%"
)
where node >nul 2>&1
if errorlevel 1 (
  echo [sim] Node.js not found. Install Node 22+ first.
  exit /b 1
)

REM 백엔드 기동 여부 확인 — 없으면 백그라운드로 시작
curl -s -o NUL "%BASE%/health"
if not errorlevel 1 goto ready

echo [sim] Backend not running on :4000 - starting backend ...
if not exist "%ROOT%\backend\node_modules" (
  pushd "%ROOT%\backend" && call npm install && popd
)
start "oojoo-farm-backend" /min cmd /c "cd /d %ROOT%\backend && npm start"
timeout /t 4 /nobreak >nul
curl -s -o NUL "%BASE%/health"
if errorlevel 1 (
  echo [sim] Backend failed to start. Run scripts\start-backend.bat manually.
  exit /b 1
)

:ready
echo [sim] Backend ready at %BASE%
cd /d "%SIM_DIR%"
node src\index.js --base %BASE% %*
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" echo [sim] Some scenarios FAILED - check log above.
exit /b %EXITCODE%
endlocal
