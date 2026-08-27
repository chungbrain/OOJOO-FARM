# 작업 일지 — 2026-08-27

## ROI 편집 화면 개선 — 카메라 이미지 표시 + 영역별 식물 입력 흐름

**요구사항:**
1. ROI 지정 대상(카메라 프리뷰)에 이미지가 보이지 않음 → 보이도록 수정
2. 이미지가 보인 상태에서 영역을 표시하고, 그 영역이 어떤 식물인지 입력(선택)하는 부분 구현

**문제 원인 (이미지 미표시):**
- ROI 편집 화면 진입 시 `CameraHost.attach()`에서 `preview` use case가 아직 초기화 전이면
  surface 연결이 조용히 누락될 수 있었음 — 로그도 없이 넘어가 사후 추적이 어려웠음
- 프리뷰 카드가 260dp로 작아 시인성이 떨어졌음

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/slave/.../vision/CameraHost.kt` | `attach()` — `preview`가 null이면 "deferred to pendingPreviewView" 로그 남기고 start 완료 시 연결되는 경로 명시. 연결 누락 진단 가능 |
| `android/slave/.../ui/RoiEditorScreen.kt` | 대규모 UX 개선: ① 카메라 카드 260dp → 300dp 확대 ② **드래그 먼저 → "이 영역의 식물은?" 선택 다이얼로그** 흐름으로 전환 (기존: 식물 먼저 선택 → 드래그) ③ 각 ROI 영역 좌상단에 식물명 라벨 오버레이 표시 ④ 드래그 종료 시 실제 뷰 크기(`pointerInput.size`)를 함께 저장해 정확한 정규화 ⑤ `onSizeChanged`로 카드 실측 크기 추적 — 라벨 위치가 화면 밀도와 무관하게 정확 |
| `values/strings.xml` (ko/en) | `roi_pick_plant_title`, `roi_pick_plant_desc`, `roi_cancel_selection` 추가, 가이드 문구 갱신 |
| `dist/oojoo-farm-farmer-debug.apk` | 재빌드 (18.3MB) |

**동작 흐름:**
1. ROI 설정 진입 → 카메라 프리뷰 표시 (기존 ROI + 식물명 라벨 오버레이)
2. 식물이 있는 영역을 드래그 → 흰색 사각형 표시
3. 드래그 종료 → "🌱 이 영역의 식물은?" 다이얼로그 → 식물 목록에서 선택
4. 선택 즉시 ROI 저장 + `FarmerEngine.onRoisChanged()`로 모니터링 재시작 → 영역에 라벨 표시
5. 이미 ROI가 있는 식물을 다시 선택하면 기존 영역 교체(✓ 표시)

**검증:**
- `:slave:app:compileDebugKotlin` ✅
- `:slave:app:assembleDebug` ✅ → dist APK 갱신

**남은 작업:**
- 실기기에서 카메라 프리뷰 표시·드래그·다이얼로그 흐름 확인
