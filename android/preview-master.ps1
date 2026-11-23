# OOJOO-FARM master 앱 디자인 미리보기 (에뮬레이터)
#
# 사용법 (저장소 루트에서):
#   powershell -ExecutionPolicy Bypass -File android\preview-master.ps1                 # 빌드+설치+실행 (에뮬레이터/기기 이미 실행 중)
#   powershell -ExecutionPolicy Bypass -File android\preview-master.ps1 -StartEmulator  # 에뮬레이터 자동 시작 포함
#
# 최초 1회 준비(이미 완료됨): emulator 패키지 + system-images;android-34;google_apis;x86_64
# 설치, AVD 'oojoo_design'(Pixel 6) 생성. 가속: WHPX.
param([switch]$StartEmulator)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot   # 저장소 루트 (이 스크립트는 android/ 아래)
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$avd = "oojoo_design"

function Wait-Boot {
    & $adb wait-for-device | Out-Null
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        $b = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($b -eq "1") { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

# 1) 실행 중인 기기/에뮬레이터 확인 — 없으면 시작
$online = (& $adb devices | Select-Object -Skip 1) -match "emulator-\d+\s+device"
if (-not $online) {
    if (-not $StartEmulator) {
        Write-Host "[!] 연결된 기기/에뮬레이터가 없습니다. -StartEmulator 옵션을 붙이면 자동 시작합니다." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "[1] 에뮬레이터($avd) 시작..." -ForegroundColor Cyan
    Start-Process $emu -ArgumentList @("-avd", $avd, "-no-snapshot", "-gpu", "auto")
    if (-not (Wait-Boot)) { Write-Host "[!] 에뮬레이터 부팅 타임아웃" -ForegroundColor Red; exit 1 }
    Write-Host "[1] 부팅 완료" -ForegroundColor Green
} else {
    Write-Host "[1] 기기/에뮬레이터 이미 실행 중" -ForegroundColor Green
}

# 2) master APK 빌드
Write-Host "[2] master APK 빌드..." -ForegroundColor Cyan
Push-Location "$PSScriptRoot\master"
try {
    & ..\gradlew.bat :master:app:assembleDebug --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
} finally { Pop-Location }
$apk = "$root\android\master\app\build\outputs\apk\debug\app-debug.apk"

# 3) 설치 + 실행
Write-Host "[3] APK 설치..." -ForegroundColor Cyan
& $adb install -r $apk | Out-Null
Write-Host "[4] 앱 실행..." -ForegroundColor Cyan
& $adb shell am start -n "com.oojoo.farm.master/com.oojoo.farm.master.MainActivity" | Out-Null
Write-Host "완료 — 에뮬레이터 화면에서 디자인을 확인하세요." -ForegroundColor Green

# 참고: 화면 캡처 (PowerShell '>' 리다이렉션은 바이너리를 깨므로 pull 방식 사용)
#   & $adb shell screencap -p /sdcard/preview.png
#   & $adb pull /sdcard/preview.png preview.png
