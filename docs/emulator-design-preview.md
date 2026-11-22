# 에뮬레이터 디자인 미리보기 환경 (2026-11-22 구축)

## 목적

- 기기에 설치하지 않고 PC 에뮬레이터에서 master 앱 디자인(농장 씬, 식물 카드, 팻말 등)을 미리 확인.

## 구축된 환경 (이 Windows PC)

| 항목 | 값 |
|---|---|
| SDK | `C:\Users\cklee\AppData\Local\Android\Sdk` |
| 에뮬레이터 | SDK `emulator` 패키지 (sdkmanager로 설치) |
| 시스템 이미지 | `system-images;android-34;google_apis;x86_64` (앱 compileSdk 34에 맞춤) |
| AVD 이름 | `oojoo_design` (Pixel 6 프로파일) |
| 가속 | WHPX (Windows Hypervisor Platform) — `emulator-check accel` 확인 완료 |
| Java | Microsoft JDK 17 (PATH) |

## 사용법

저장소 루트에서:

```powershell
# 에뮬레이터가 꺼져 있으면 자동 시작 후 빌드→설치→앱 실행
powershell -ExecutionPolicy Bypass -File android\preview-master.ps1 -StartEmulator

# 에뮬레이터가 이미 떠 있는 경우 (빌드→설치→실행만)
powershell -ExecutionPolicy Bypass -File android\preview-master.ps1
```

앱이 뜨면 서버 주소에 배포된 백엔드(`http://34.50.40.04:4000` 등)를 입력하고
기존 계정으로 로그인하면 실제 농장 데이터로 디자인 확인 가능.

## 화면 캡처 (디자인 비교용)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell screencap -p /sdcard/preview.png
& $adb pull /sdcard/preview.png preview.png
```

주의: PowerShell의 `>` 리다이렉션은 PNG 바이너리를 깨므로 반드시 `adb pull` 방식 사용.

## 기타

- 에뮬레이터 종료: 창 닫기 또는 `adb -s emulator-5554 emu kill`
- slave(farmer) 앱 미리보기가 필요하면 동일 패턴으로 확장 가능 (`:slave:app:assembleDebug`)
