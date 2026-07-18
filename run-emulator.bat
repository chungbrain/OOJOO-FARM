@echo off
setlocal
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\emulator;%PATH%

set ROOT=%~dp0
set AVD=oojoo_pixel
set APK=%ROOT%dist\master-debug.apk
if not exist "%APK%" set APK=%ROOT%android\master\app\build\outputs\apk\debug\app-debug.apk

echo [OOJOO FARM] Starting backend on :4000 ...
start "OOJOO Backend" cmd /c "%ROOT%start-backend.bat"

echo [OOJOO FARM] Starting emulator "%AVD%" (stable GPU flags)...
REM angle_indirect is more stable than host GPU on many Windows laptops;
REM Filament 3D is disabled inside the app when running on emulator.
start "" "%ANDROID_HOME%\emulator\emulator.exe" -avd %AVD% -gpu angle_indirect -no-snapshot -no-boot-anim -memory 3072

echo [OOJOO FARM] Waiting for device...
adb wait-for-device
:waitboot
adb shell getprop sys.boot_completed 2>nul | findstr 1 >nul
if errorlevel 1 (
  timeout /t 3 /nobreak >nul
  goto waitboot
)

echo [OOJOO FARM] Port reverse (emulator localhost:4000 -> PC :4000)...
adb reverse tcp:4000 tcp:4000 >nul 2>&1

echo [OOJOO FARM] Installing APK...
adb install -r "%APK%"

echo [OOJOO FARM] Pointing app to http://10.0.2.2:4000/ ...
adb shell "run-as com.oojoo.farm.master mkdir -p shared_prefs" >nul 2>&1
adb shell "run-as com.oojoo.farm.master sh -c \"echo ^<?xml version='1.0' encoding='utf-8' standalone='yes' ?^>^<map^>^<string name='serverUrl'^>http://10.0.2.2:4000/^</string^>^</map^> > shared_prefs/master_prefs.xml\"" >nul 2>&1

echo [OOJOO FARM] Launching app...
adb shell am force-stop com.oojoo.farm.master
adb shell am start -n com.oojoo.farm.master/.MainActivity
echo [OOJOO FARM] Done.
echo   Backend window: "OOJOO Backend"
echo   App URL: http://10.0.2.2:4000/
echo   Note: Emulator uses 2D farm preview (Filament off) to avoid qemu crashes.
endlocal
