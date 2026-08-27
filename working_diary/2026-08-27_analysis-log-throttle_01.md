# 작업 일지 — 2026-08-27

## Farmer 건강분석 이벤트 로그 10초 스로틀

**요구사항:**
- Farmer의 건강분석 이벤트 로그가 프레임마다 발생함 → 10초에 한 번만 발생하도록 설정

**문제 원인:**
- `CameraHost`의 `ImageAnalysis` 애널라이저가 카메라 프레임마다(초당 수십 회) `FarmerEngine.onAnalysis()`를 호출
- `onAnalysis()`가 매 호출마다 `addLog()`로 "분석: ..." 로그를 누적 → 대시보드 이벤트 로그가 분석 로그로 도배됨

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/slave/.../service/FarmerEngine.kt` | `onAnalysis()`에 10초 스로틀 추가 — `lastAnalysisLogAt` 타임스탬프로 로그 기록을 10초에 1회로 제한. `_lastAnalysis.value` 갱신은 프레임마다 유지 (자동 관수 판정 `tick()`과 대시보드 표시의 실시간성 보존) |

**동작 흐름:**
- 프레임 분석 → 상태 갱신은 매 프레임 → 로그는 마지막 기록 후 10초 경과 시에만 추가 (10,000ms 스로틀)
- "분석 불가/실패" 등 무효 결과는 기존과 동일하게 로그에서 제외

**검증:**
- `:slave:app:compileDebugKotlin` ✅
- `:slave:app:assembleDebug` ✅ → dist APK 갱신 (18.3MB)

**남은 작업:**
- 실기기에서 이벤트 로그가 10초 간격으로 기록되는지 확인
