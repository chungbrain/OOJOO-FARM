# 작업 일지 — 2026-08-23

## ROI 기반 다중 식물 건강 모니터링 (commit `roi-per-plant-monitoring`)

**요구사항:**
- Farmer에 할당된 식물마다 이미지에서 ROI 지정
- 각 ROI 영역만 잘라 해당 식물의 건강 상태를 주기적으로 모니터링
- 하나의 이미지에 서로 다른 ROI에 서로 다른 식물이 있으면, 각각 자기 식물의 모델로 판정

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/slave/.../vision/RoiStore.kt` (신규) | ROI 저장소 — plantId별 정규화(0~1) 사각 ROI, `files/rois.json`에 JSON 저장. 식물당 1개 ROI (덮어쓰기) |
| `android/slave/.../vision/PlantAnalyzer.kt` | `analyzeRoi(bitmap, roi)` — 프레임에서 ROI만 crop해 진단. 해당 식물 모델 있으면 CNN, 없으면 휴리스틱 |
| `android/slave/.../vision/PlantHealthNet.kt` | `diagnoseWithModel(modelId, bitmap)` — 모델 직접 지정 추론 (프레임 스킵 없음). `modelIdFor(species)` 헬퍼 |
| `android/slave/.../ui/RoiEditorScreen.kt` (신규) | ROI 설정 화면 — 카메라 프리뷰 위 드래그로 사각 영역 지정, 식물 선택 후 매핑, 색상별 ROI 오버레이, 목록에서 삭제 |
| `android/slave/.../service/FarmerEngine.kt` | `roiMonitorLoop()` — 20초 주기로 전체 ROI 진단. `RoiStatus` StateFlow (plantId별 최신 상태). 물부족/해충 시 식물별 이벤트 보고 (15분 debounce). `plants` public 노출, `onRoisChanged()` |
| `android/slave/.../vision/CameraHolder.kt` | `captureFrame()` — 메모리상 Bitmap 프레임 캡처 (ROI 모니터링용, 디스크 I/O 없음) |
| `android/slave/.../ui/DashboardScreen.kt` | "ROI 식물별 모니터링" 카드 — 식물별 상태/모델/시각 표시, 이상 시 빨간색. ROI 편집 진입 버튼 |
| `android/slave/.../MainActivity.kt` | `roi` 라우트 추가 |
| values / values-en strings.xml | ROI 관련 다국어 문자열 14개 |

**동작 흐름:**
1. 대시보드 → "📐 ROI 설정" → 식물 선택 → 카메라에서 해당 식물 영역 드래그 → 저장
2. 엔진이 20초마다 카메라 프레임 캡처 → 각 ROI별 crop → 해당 식물 종의 모델(예: 바질→basil.bin, 토마토→cherry_tomato.bin)로 진단
3. 결과는 대시보드 "ROI 식물별 모니터링" 카드에 실시간 표시
4. 물 부족/해충 감지 시 백엔드로 식물별 이벤트 전송 (마스터 알림)

**모델 매핑:** 종명 → assets/models/{species}.bin (basil, cherry_tomato, cactus, herb, strawberry, pepper, pumpkin, zucchini). 매핑 없는 식물은 색 통계 휴리스틱으로 판정 (modelId="heuristic" 표시)

**검증:**
- `:slave:app:compileDebugKotlin` ✅
- `:slave:app:assembleDebug` ✅ → `dist/oojoo-farm-farmer-debug.apk` 갱신 (18.3MB)

**빌드 이슈 메모:**
- `assembleDebug`의 dist 자동 복사 훅이 최근 빌드에서 동작하지 않아 수동 복사함 — 이후 확인 필요
- Compose `Rect(topLeft, bottomRight)` 확장과 기본 생성자 충돌 주의 (해결: 명시적 left/top/right/bottom)
- `detectDragGestures` import는 `androidx.compose.foundation.gestures` 소속

**남은 작업:**
- 실기기에서 ROI 드래그 UX 테스트
- ROI별 진단 결과를 마스터 앱에도 표시 (현재는 Farmer 대시보드만)
- ROI 진단 주기를 정책(policy)에서 원격 조정
