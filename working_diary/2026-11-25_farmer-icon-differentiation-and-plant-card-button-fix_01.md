# 2026-11-25 — farmer 아이콘 색 틴트 제거(아이콘 종류로 구별) + 식물카드 나눔/판매 버튼 찌그러짐 수정

## 요구사항

1. farmer 아이콘에 색 틴트를 주지 말고 **아이콘을 원본 그대로** 사용한다.
   farmer 간의 차이는 **서로 다른 아이콘**으로 구별한다.
2. 식물카드의 나눔/판매 버튼이 찌그러져 보인다 — UI를 수정한다.

## 문제 원인

### 1. farmer 색 틴트 방식

- 기존: `farmerColorFor(slaveId)`가 farmer ID 해시로 8색 팔레트 중
  색 하나를 정하고, farmer 아이콘 원 배경을 그 색으로 물들였다
  (farmer 목록 30% 틴트, 농장 씬 figure 30% 틴트, 식물카드 pill은
  색 배경 + 흰 글자, 배정 다이얼로그는 색 원).
- 아이콘(🤖) 자체는 모든 farmer가 동일해서 색 틴트로만 구별했으나,
  원본 아이콘 색감을 훼손한다는 지적.

### 2. 식물카드 나눔/판매 버튼 찌그러짐

- `PlantGridCard`가 `.aspectRatio(0.82f)`로 **높이를 폭에 비례해 고정**.
- 내용(48sp 이모지 + 이름 + 생육 단계 pill + farmer pill/건강 pill +
  나눔/판매 Row)이 좁은 화면에서 고정 높이를 초과하면
  `Arrangement.SpaceBetween` Column이 공간을 못 찾아 하단 버튼 Row가
  눌리거나 잘려 "찌그러져" 보임.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ui/FilamentViewer.kt` | `FarmerPalette`(8색)+`farmerColorFor()` 삭제 → **`FarmerIconPalette`(🤖👾🐝🦊🐻🐸🐥🐨)+`farmerIconFor()`** 추가. farmer ID 해시로 안정 선택 — 어느 화면에서든 같은 farmer는 같은 아이콘 |
| `ui/FilamentViewer.kt` — FarmerFigure | 파라미터 `faceColor: Color` → `icon: String`. 배경 색 틴트 제거 → **흰 원(OojooTheme.Card) + 잉크 테두리 + 원본 아이콘**. facing/tilt/scale/관수 물방울 애니메이션 유지 |
| `ui/FarmerListScreen.kt` | farmer 목록 카드 아이콘: 색 틴트 배경 → 흰 배경 + farmer별 아이콘. 모니터링 설정 다이얼로그 제목도 `farmerIconFor` 사용 |
| `ui/PlantListScreen.kt` — farmer pill | 색 배경+흰 글자 → **녹색 배경(GreenBg) + GreenDark 글자 + farmer별 아이콘** |
| `ui/PlantListScreen.kt` — AssignFarmerDialog | 색 원 + 🤖 조합 → farmer별 아이콘 하나로 단순화 |
| `ui/PlantListScreen.kt` — PlantGridCard | **`.aspectRatio(0.82f)` 제거** — 높이는 콘텐츠에 맞춤. Column `fillMaxSize+SpaceBetween` → `fillMaxWidth+spacedBy(8dp)`. 나눔/판매 버튼: 세로 패딩 4→6dp, `maxLines=1`+`softWrap=false`로 줄바꿈 찌그러짐 차단 |
| `ui/GalleryScreen.kt` | 앨범 farmer 헤더: 색 동그라미 → farmer별 아이콘 텍스트 |
| `dist/oojoo-farm-master-debug.apk` | 신규 빌드 사본 |

## 동작 흐름

- farmer 등록/연결 시 부여받은 `slaveId`가 바뀌지 않는 한
  `farmerIconFor(slaveId)`는 항상 같은 아이콘을 반환 →
  농장 씬 figure, farmer 목록, 식물카드 pill, 배정 다이얼로그,
  사진첩 헤더 전부 동일 아이콘으로 표시.
- 식물카드는 이제 내용만큼 세로로 늘어나므로 건강 pill/farmer 유무와
  무관하게 하단 나눔/판매 버튼이 온전한 높이로 렌더된다.

## 검증 결과

- `:master:app:compileDebugKotlin` — BUILD SUCCESSFUL
- `:master:app:assembleDebug :slave:app:assembleDebug --no-daemon` —
  BUILD SUCCESSFUL in 17s
- dist 수동 복사: `oojoo-farm-master-debug.apk` 갱신 확인
  (farmer APK는 이번 변경에 포함되지 않아 기존 빌드 유지)
- 커밋: `fix(master)` 코드 4파일 → `build` APK → diary docs

## 남은 작업

- 실기기/에뮬레이터에서 farmer 2대 이상 연결해 서로 다른 아이콘으로
  구별되는지, 농장 씬에서도 아이콘이 제대로 보이는지 최종 확인
- farmer 아이콘 종류(8종)를 사용자가 직접 고르게 하는 옵션은 요청 시
