# 2026-11-22 — 농장 3x4 구역 + 식물 등록/상세 위치 선택기

## 요구사항

- 나의 농장은 녹색 지면으로 정해진 구역 안에 **3행 × 4열** 배치 공간을 갖는다.
- 식물 등록 시 또는 식물 상세(편집)에서 3x4 칸 중 한 곳을 직접 고를 수 있게 한다.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ui/FarmGrid.kt` (신규) | 공용 그리드 정의 — `FARM_ROWS=3, FARM_COLS=4`, `FarmCell`, `clampFarmCell`(과거 4x4 저장값 호환), `pickEmptyFarmCell`(자동 배치 규칙), `computeFarmOccupancy`(점유 현황), `FarmCellPicker`(3x4 칸 선택 UI) |
| `ui/FilamentViewer.kt` | 농장 씬 4x4 → **3x4** 전환 (private GRID/PlantCell 제거, 공용 정의 사용), 저장 위치 로드 시 클램프 |
| `ui/PlantRegistrationScreen.kt` | 등록 폼에 농장 위치 선택기 추가 — 기본 추천(가장 좋은 빈 칸), 등록 성공 시 `Prefs.setFarmPlantCell(신규ID, 칸)` |
| `ui/PlantDetailScreen.kt` | 편집 카드에 위치 선택기 추가 — 점유 칸 선택 시 저장 시점에 맞바꿈(`applyFarmCell`) |
| `values(,-en)/strings.xml` | `farm_position_label/hint/applied` 문자열 추가 |

## 동작 설계

- **위치 저장**: 기존과 동일하게 master 앱 로컬 Prefs(`farmPlantCells`) — 홈 농장 씬,
  드래그 이동, 등록/편집 선택기가 모두 같은 저장소를 공유. 백엔드 변경 없음.
- **점유 표시 일관성**: 선택기의 `computeFarmOccupancy`는 FarmSceneView의 배치 규칙
  (저장 위치 우선 → 자동 배치)을 그대로 재사용 — 선택기에서 보이는 위치 = 홈 씬 위치.
- **충돌 처리**: 편집에서 다른 식물이 점유한 칸을 고르면 → 그 식물을 내 예전 칸으로
  맞바꿈(예전 칸 없으면 가장 가까운 빈 칸으로 이동).
- **과거 데이터 호환**: 4x4 시절에 저장된 row/col=3 위치는 `clampFarmCell`로 row=2로
  접힘 — 앱 재시작 후에도 3x4 안에 안전하게 배치됨.

## 검증 결과

- `:master:app:compileDebugKotlin` — BUILD SUCCESSFUL (기존 deprecation 경고만)
- `:master:app:assembleDebug` — BUILD SUCCESSFUL
- 에뮬레이터(oojoo_design) 설치 + 실행 성공 → 위치 선택기 디자인 즉시 미리보기
- dist 갱신 (2026112206), 커밋 `cb042bc` → push 완료

## 남은 작업

- 에뮬레이터/실기기에서 확인: ①홈 농장 씬 3x4 (행이 3개로, 식물 원이 더 큼)
  ②등록 화면 위치 선택기(점유 칸 이모지 표시) ③상세 편집에서 위치 맞바꿈 동작
- 위치를 백엔드 DB에 저장해 기기 간 동기화하려면 별도 스키마 작업 필요(현재 로컬 Prefs)
