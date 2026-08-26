# 작업 일지 — 2026-08-27

## ROI 진단 결과 백엔드 업로드 + 진단 주기 원격 조정 (commit `2c75e4d`)

**요구사항 (2026-08-23 ROI 작업의 후속 과제):**
1. ROI 진단 결과를 Master 앱에도 표시 (기존: Farmer 대시보드만)
2. ROI 진단 주기를 policy에서 원격 조정 (기존: 20초 고정)

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `backend/src/db.js` | `policies` 테이블에 `roi_interval INTEGER DEFAULT 20` 컬럼 마이그레이션 (`ensureColumn`) |
| `backend/src/routes/policy.js` | GET/PUT 정책에 `roi_interval` 추가 (10~3600초 범위 클램프) |
| `android/slave/.../model/Models.kt` | `PolicyResponse.roi_interval`, `AnalysisPayload`, `AnalysisReportRequest/Response` 신규 |
| `android/slave/.../network/ApiService.kt` | `POST api/analysis/report` 추가 |
| `android/slave/.../service/FarmerEngine.kt` | ① ROI 진단 결과를 식물별로 `/api/analysis/report` 업로드 (5분 스로틀, 이상 감지 시 1분) ② `roiMonitorLoop`가 `_roiIntervalSec` 동적 주기 사용 ③ 하트비트 루프 10회(=5분)마다 `syncPolicy()` 재동기화 → 주기 변경이 재시작 없이 반영 |
| `android/master/.../model/Models.kt` | `PolicyRequest.roiInterval`, `PolicyResponse.roi_interval`, `AnalysisData.modelId` 추가 |
| `android/master/.../ui/FarmerListScreen.kt` | Farmer 카드에 "⚙️ 모니터링 설정" 버튼 + `MonitoringPolicyDialog` — ROI 진단 주기(초) 입력 후 `PUT /api/policy/:slaveId` 저장. VM에 policy 상태/`openPolicy`/`savePolicy` 추가 |
| `android/master/.../ui/PlantDetailScreen.kt` | 건강 정보 요약 카드에 "AI 모델" 행 추가 (modelId 없으면 "색상 통계") |
| `values/strings.xml`, `values-en/strings.xml`, `AppLocale.kt`, `LocalizedMessages.kt` | 모니터링 설정/ROI 주기/AI 모델 문자열 7개 (ko/en) |

**동작 흐름:**
1. Farmer: ROI 루프(동적 주기) → 각 ROI 진단 → 식물별 결과 업로드 → Master가 기존 `GET /api/analysis/latest/:plantId`로 표시 (식물 상세 건강 카드 + 목록 배지)
2. Master: Farmer 관리 → ⚙️ 모니터링 설정 → 주기(초) 입력/저장 → 백엔드 policies 갱신
3. Farmer: 최대 5분 내 정책 재동기화 → 로그에 "ROI주기:N초" → 다음 사이클부터 새 주기 적용

**검증:**
- `node --check` (policy.js, db.js) ✅
- `:slave:app:compileDebugKotlin` + `:master:app:compileDebugKotlin` ✅
- `assembleDebug` 양쪽 ✅ → dist APK 2종 갱신 (farmer 18.2MB / master 26.6MB)

**남은 작업 (사용자 과제):**
- 실기기 테스트: 백엔드 재시작 → Farmer 앱 업데이트 → ROI 설정 → Master에서 주기 10초 변경 → 5분 내 Farmer 로그 반영 확인 → Master 식물 상세에서 식물별 상태/AI 모델 표시 확인
