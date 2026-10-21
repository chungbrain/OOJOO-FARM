# 작업 일지 — 2026-10-22 (2차 세션)

## Android Farmer 기능 이식 (Jev 건강등급 / 해충 전용 CNN / 변화 알림 / 자동 가로·세로 촬영)

**요구사항:** 1차 세션(2026-10-22 farmer-features-pc-simulation)에서 PC 시뮬레이션으로 검증 완료한 farmer 기능 4종을 Android(slave+master)에 이식 — docs/farmer-features.md §6 매핑 그대로 수행.

**커밋 (`585da18..9b10ddb` push 완료):**

| 커밋 | 파일 | 내용 |
|---|---|---|
| `a41a2f2` | `simulation/src/engine/farmerSim.js`, `simulation/src/index.js`, `simulation/test/engine.test.js`, `backend/test/farmer_features.test.js`, `docs/farmer-features.md` | health_change payload를 **플랫 키**(pestFrom/pestTo/pestLevelKo/…)로 정합 — Android `EventRequest`가 `Map<String,String>`이라 중첩 객체를 못 보내므로, 시뮬·백엔드 테스트·스펙을 Android가 보낼 수 있는 형태로 통일 |
| `2cdc781` | slave 10파일 (신규 4: `Ojp1.kt`·`HealthGradeNet.kt`·`PestNet.kt`·`OrientationPolicy.kt`) | **F1/F2/F3/F4 slave 이식** — 아래 상세 |
| `9b10ddb` | master 6파일 (`Models.kt`, `AppLocale.kt`, `strings.xml`×2, `NotificationScreen.kt`, `PlantDetailScreen.kt`) | **F3 알림 표시** — noti 센터 health_change 라벨/색상(악화=주황, 개선=청록)+전이 상세("건강 → 나쁨 · 🐛 심각발생"), 식물 상세 건강 카드에 건강등급/해충발생 행 추가 (한·영 로캘) |

**slave 이식 상세 (커밋 `2cdc781`):**

| 파일 | 내용 |
|---|---|
| `vision/Ojp1.kt` 신규 | OJP1 공유 런타임 — parse/load(assets 캐시)/forward/bitmapToRgb48/argmaxSoftmax. PlantHealthNet의 private 구현을 추출해 3개 모델(종별 6클래스/등급 5단계/해충 4단계)이 동일 연산 공유. 시뮬(ojp1.js)과 동일 수학 |
| `vision/PlantHealthNet.kt` | Ojp1 위임으로 리팩터(316→176줄) — infer는 `model.argmaxSoftmax` 재사용. 추론 결과·API 불변 |
| `vision/HealthGradeNet.kt` 신규 | F1 — `health_grade.bin` 5단계(매우건강/건강/보통/나쁨/매우나쁨) 판정, 종 무관 범용 |
| `vision/PestNet.kt` 신규 | F2 — `pest.bin` 4단계(없음/경미/보통/심각), 건강진단과 **별도 모델**, `isDetected`(light 이상) 퇴치 트리거용 |
| `vision/PlantAnalyzer.kt` | `AnalysisResult`에 등급/해충 필드 확장 — `analyzeRoi`가 동일 ROI crop으로 HealthGradeNet+PestNet까지 추론해 병합 |
| `vision/OrientationPolicy.kt` 신규 | F4 — camera_mount(auto/landscape/portrait) 정책 상태 + 리졸버(시뮬 orientation.js와 동일 수학). syncPolicy가 갱신 |
| `vision/CameraHost.kt` | bindToLifecycle 반환 Camera에서 `sensorRotationDegrees` 확보 → `applyTargetRotation`이 **mount 정책+센서 각** 기준으로 targetRotation 결정 (auto=디스플레이 회전, 고정 설치=명시 방향). `lastPlan` 노출로 촬영 방향 로그 |
| `service/FarmerEngine.kt` | F3 — `runRoiMonitoring`에 등급/해충 전이 추적 추가: **연속 2회 관측 확정**(단일 사이클 오분류 플리커 방지) 후 `health_change` 이벤트(플랫 payload). 해충 `light+` → pest_detected 이벤트도 PestNet 기준으로. 분석 리포트(AnalysisPayload)에 등급/해충 포함, 업로드 스로틀 이상 판정에 poor/very_poor 추가. syncPolicy camera_mount → OrientationPolicy. capture_photo 촬영 직전 방향 재적용+로그 |
| `model/Models.kt` | `PolicyResponse.camera_mount` + `AnalysisPayload` 등급/해충 필드 |
| `ui/DashboardScreen.kt` | ROI 현황 카드에 "① 건강: 보통 (91%) · 🐛 해충: 해충없음" 등급 요약 행 (등급별 색상) |

**동작 흐름 (기기에서):**
1. ROI 모니터링(기본 20초)마다 각 ROI crop을 종별 CNN + HealthGradeNet(등급) + PestNet(해충)으로 3중 추론 → 분석 리포트 업로드
2. 등급/해충 상태가 **연속 2회 동일하게 다르게 관측**되면 `health_change` 이벤트 → 백엔드가 master SSE push + 알림 센터 집계 → master 앱이 "🌿 건강상태 변화 · 건강 → 나쁨" 표시
3. master capture_photo → farmer는 camera_mount 정책(auto/가로/세로 고정)에 따라 가로/세로 사진 자동 촬영

**검증:**
- `gradlew :slave:app:compileDebugKotlin` — **통과** (1차 오류: CameraX 1.3.4의 `CameraInfo`에는 `sensorOrientation`이 아닌 `sensorRotationDegrees`가 있음 → 수정)
- `gradlew :master:app:compileDebugKotlin` — **통과**
- `node scripts/verify-locales.mjs` — 신규 문자열 3개(noti_health_change/health_grade_label/pest_level_label) 한·영 정합 **통과**
- 시뮬레이션 `npm test` **15/15** + 시나리오 **S1~S4 전체 통과** (플랫 payload 반영 후 재검증 — S1 85.3%, S2 81.7%, S3 4건 전이 체인 정상, S4 4/4)
- 백엔드 `npm test` **28/28** (플랫 payload 테스트 반영)
- `git push origin main` — `585da18..9b10ddb` 완료

**남은 작업:**
- 실기기 E2E — APK 빌드(`build-apk-dist`) 후 실 카메라로 등급/해충 추론·촬영 방향·알림 수신 확인 (실물 화면은 합성 분포와 다름 — 웹 일반화 36%의 교훈, 진단 확정 라벨 파인튜닝 경로 연결 필요)
- GCP 백엔드 배포 (camera_mount 정책/이벤트 SSE push 반영) — 요청 시 `deploy-backend-gcp`
- FCM 푸시(현재는 앱 내 알림 센터+SSE), 루트 `PC` 파일 삭제 여부 결정 (이월)

**특이사항:**
- 시뮬레이션에서 이미 잡아둔 결함들(전이 확정 규칙, 색 공간 분리, 플랫 payload)이 이식 단계에서 그대로 스펙으로 반영됨 — 1차 PC 선행 검증의 실익 확인
- CameraX `bindToLifecycle`이 `Camera`를 반환한다는 점을 활용해 센서 각을 확보 (기존 코드는 반환값을 버렸음) — 3개 fallback 바인딩 경로 모두에 적용
- master NotificationScreen의 미사용 `notiColor` 함수(원래 전 노티가 고정 Yellow)를 실제 카드 색상으로 활성화 — 알림 유형별 색상이 설계 의도대로 반영됨
