# 2026-11-22 — 에뮬레이터 디자인 미리보기 환경 구축

## 요구사항

- 기기 설치 없이 PC 에뮬레이터에서 master 앱 디자인을 미리 볼 수 있게 준비.

## 구축 내용 (인프라 작업 — 로컬 환경)

| 단계 | 내용 |
|---|---|
| 1 | SDK 상태 확인 — adb는 있으나 emulator 패키지/시스템 이미지 없음 |
| 2 | `sdkmanager`로 `emulator` + `system-images;android-34;google_apis;x86_64` 설치 (compileSdk 34에 맞춤) |
| 3 | `avdmanager`로 AVD `oojoo_design`(Pixel 6) 생성, `emulator-check accel` → **WHPX 사용 가능** |
| 4 | 에뮬레이터 부팅 → dist master APK(2026112205) 설치 → `MainActivity` 실행 성공 |
| 5 | 재사용 스크립트 `android/preview-master.ps1` + 사용법 문서 `docs/emulator-design-preview.md` 작성 |

## 변경 파일 (커밋)

| 파일 | 내용 |
|---|---|
| `android/preview-master.ps1` | 빌드→설치→앱 실행 원커맨드 스크립트 (`-StartEmulator`로 에뮬레이터 자동 시작+부팅 대기) |
| `docs/emulator-design-preview.md` | 구축 환경 상태, 사용법, 화면 캡처 방법(`adb pull` — PS `>` 리다이렉션은 PNG 깨짐) |

커밋: `ca95dea build: add emulator design preview setup ...` → push 완료

## 사용법

```powershell
powershell -ExecutionPolicy Bypass -File android\preview-master.ps1 -StartEmulator
```

앱 실행 후 서버 주소 `http://34.50.40.104:4000` 입력 → 기존 계정 로그인 →
농장 씬(구름/새 애니메이션, 팻말), 내 식물 카드 크기 등 오늘 수정한 디자인 확인.

## 검증 결과

- sdkmanager 설치 완료, AVD 생성 완료
- 에뮬레이터 부팅 BOOT_COMPLETED (5분 내)
- `adb install -r dist\oojoo-farm-master-debug.apk` → Success
- `am start MainActivity` → Starting OK (화면 캡처 232KB 정상 생성 — 모델이 이미지를 볼 수
  없어 최종 화면 확인은 사용자 몫)

## 남은 작업

- 사용자가 에뮬레이터 화면에서 직접 디자인 확인 (농장 하늘 애니메이션, 팻말, 카드 크기)
- 필요 시 slave(farmer) 앱 미리보기 스크립트 확장
