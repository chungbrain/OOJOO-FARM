# 2026-11-24 — 농장 씬 farmer 아이콘 탭 통일 + 위치 초기화 버그 수정

## 요구사항

1. 나의 농장(농장 씬)의 farmer 아이콘을 farmer 탭의 아이콘과 동일하게 바꾼다.
2. 나의 농장에서 식물 위치는 한번 지정·저장하면 변하지 않아야 한다.
   (지금은 계속 초기화되는 것 같다는 제보)

## 문제 원인

### 1. 아이콘 불일치

- farmer 탭(FarmerListScreen): 🤖 이모지 + farmer 색상 30% 틴트 원 + 잉크 테두리
- 농장 씬(FarmerFigure): Canvas 프리미티브 커스텀 로봇(안테나·몸통·바퀴)
  → 같은 farmer인데 화면별로 전혀 다른 모양으로 보임.

### 2. 위치 초기화 버그 — `FarmSceneView` 첫 프레임 wipe

- HomeScreen은 `vm.plants`가 **빈 목록**으로 시작(백엔드 응답 전)하고,
  FarmSceneView가 그 상태로 먼저 compose된다.
- 기존 코드는 빈 목록에서 `cells`를 빈 맵으로 접고, `LaunchedEffect`가
  `removeFarmPlantCells`를 **저장된 전체 위치에 대해** 실행 → 저장 위치 전부 삭제.
- 식물 목록이 도착하면 `pickEmptyFarmCell`로 목록 순서대로 전면 재배정 →
  "위치를 저장해도 앱을 다시 켜면 초기화된다"는 증상의 정체.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ui/FilamentViewer.kt` — FarmerFigure | Canvas 커스텀 로봇 → **탭과 동일 스타일**(farmer 색 틴트 원 + 🤖 이모지). 순찰(facing/tilt/scale)·관수 물방울(tending) 애니메이션은 유지. 씬 셀 크기에 맞게 40dp/22sp로 축소 렌더 |
| `ui/FilamentViewer.kt` — cells 계산 | `currentIds.isEmpty()`면 저장된 `cellMap`을 그대로 반환 (빈 맵 접기 제거) |
| `ui/FilamentViewer.kt` — LaunchedEffect | 목록이 비어 있으면(로드 전) 위치 저장/정리를 건너뛰어 저장 위치 완전 삭제 방지 |
| `ui/FilamentViewer.kt` — 그 외 | `drawFarmerFigure`·blink 애니메이션·미사용 import 제거 (`-88 lines`) |
| `master/slave build.gradle.kts` | versionCode 2026112401 → **2026112402** |
| `dist/*.apk` | 신규 빌드 본사본 |

## 검증 결과 (에뮬레이터 oojoo_design, 2026112402 설치)

- **위치 유지 — 네트워크 차단 재시작**: `svc wifi/data disable` 후 재시작
  (식물 목록 로드 실패 = 빈 목록 compose) → `run-as`로 prefs 확인:
  `farmPlantCells` 3개 위치(`1,1 / 0,1 / 2,1`) **그대로 유지** ✓
  (기존 코드라면 이 시나리오에서 전부 삭제됨)
- **위치 유지 — 정상 재시작**: 네트워크 복구 후 force-stop → 재시작 →
  동일 3개 위치 유지 ✓
- **🤖 아이콘**: uiautomator dump로 농장 씬 내 `🤖` 노드 확인 ✓
  (farmer 색 틴트 원 + 잉크 테두리 = farmer 탭 카드와 동일 스타일)
- `:master:app:compileDebugKotlin` — BUILD SUCCESSFUL
- APK 빌드(2026112402) — BUILD SUCCESSFUL in 1m 02s
- 스크린샷: `%LOCALAPPDATA%\Temp\opencode\farm_scene_2026112402.png`

## 남은 작업

- 복수 farmer 환경(실기기 farmer 2대 이상)에서 farmer별 색상 틴트 구분 확인
- 드래그 이동/등록/편집 위치 선택기가 새 guard와 함께 의도대로 동작하는지
  실기기 최종 확인
