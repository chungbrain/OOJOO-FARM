# 작업 일지 — 2026-08-27

## 해충/건강 모니터링 판정 10초 스로틀 + 서버 주소 정정

**요구사항:**
1. Farmer의 해충감지/건강상태 모니터링이 너무 빈번함 → 10초마다 진행하도록 수정
2. 서버 주소가 잘못 기재됨 (`34.50.20.236:4000`) → 올바른 주소 `34.50.40.104:4000`로 수정

**문제 원인:**
- `tick()`의 수확/해충 자율 파이프라인이 하트비트 루프(30초)마다 실행되지만,
  카메라 프레임 분석 결과(`_lastAnalysis`)가 프레임마다 갱신되어 어두운 프레임/노이즈가
  섞인 순간 판정이 그대로 해충 감지로 이어질 수 있었음
- `handlePest()` 실행 자체는 PEST_COOLDOWN(15분) 쿨다운이 있으나, 판정 진입이
  주기 제한 없이 매 tick마다 수행됨
- 서버 주소는 이전 세션에서 사용자가 알려준 IP(`34.50.20.236`)를 그대로 반영했으나,
  실제 GCP VM의 고정 IP는 `34.50.40.104` (deploy-backend-gcp 스킬 문서 기준) — 잘못된 IP였음

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/slave/.../service/FarmerEngine.kt` | `shouldRunPestCheck()` — 해충/수확 판정 진입을 10초에 1회로 제한 (`PEST_CHECK_THROTTLE = 10s`). `tick()`의 수확/해충 파이프라인이 이 게이트를 통과할 때만 실행 |
| `android/master/app/src/main/assets/server_config.yaml` | `server_url` → `http://34.50.40.104:4000/` |
| `android/slave/app/src/main/assets/server_config.yaml` | `server_url` → `http://34.50.40.104:4000/` |
| `dist/oojoo-farm-farmer-debug.apk` | 재빌드 (18.3MB) |

**동작 흐름:**
- 카메라 프레임 분석 → `_lastAnalysis` 갱신 (매 프레임, UI 표시용)
- 해충/수확 판정 → 10초 스로틀 통과 시에만 진입 → `pestSuspected`이고 15분 쿨다운 경과 시 `handlePest()` (Fan/Laser 대응 + 이벤트 전송)
- ROI 모니터링 루프는 이미 정책상 최소 10초(`coerceIn(10, 3600)`) 보장 — 추가 변경 없음

**검증:**
- `:slave:app:assembleDebug` ✅ → dist APK 갱신
- `GET http://34.50.40.104:4000/health` → `{"ok":true,"service":"oojoo-farm"}` ✅ (GCP 서버 정상)

**남은 작업:**
- Master APK는 서버 주소 변경만 있으므로 재빌드 필요 시 요청 (앱 내 설정에서도 주소 변경 가능)
- 실기기에서 해충 감지 이벤트가 10초 이하 간격으로 발생하지 않는지 확인
