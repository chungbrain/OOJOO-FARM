# 2026-11-22 — 내 식물 카드 화면 크기 비례 확대 방지

## 요구사항 / 문제 원인

- **요구**: 내 식물(PlantListScreen)의 식물 카드가 화면 크기가 변해도 비율적으로
  확대되지 않았으면 좋겠다.
- **원인**: 카드가 `fillMaxWidth().aspectRatio(0.82f)` — 그리드 셀 폭(=화면 폭 비례)을
  그대로 채우고 높이도 비율 고정 → 가로 모드/태블릿/큰 화면에서 카드가 화면만큼 커짐.

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `android/master/.../ui/PlantListScreen.kt` | 카드 최대 폭 180dp 제한 + 그리드 셀 안 가운데 정렬 |
| `dist/oojoo-farm-master-debug.apk` | 재빌드 (2026112205) |

## 상세 변경

1. **카드 최대 폭 제한**: `.widthIn(max = 180.dp).fillMaxWidth().aspectRatio(0.82f)`
   - 일반 폰(카드 폭 약 174dp): 기존과 동일한 크기 — 회귀 없음
   - 가로 모드/태블릿(셀 폭 300dp+): 180dp에서 더 이상 커지지 않음 (높이도 함께 고정)
   - 순서 주의: `widthIn`이 `fillMaxWidth` **바깥**에 와야 상한이 적용됨
     (반대 순서면 fillMaxWidth가 전체 폭을 강제해 제한이 무시됨)
2. **셀 안 가운데 정렬**: `items` 람바에서 `Box(fillMaxWidth, contentAlignment = Center)`로
   카드를 감싸 넓은 화면에서 카드가 셀 왼쪽에 붙지 않게 함

## 검증 결과

- `:master:app:assembleDebug --no-daemon` — **BUILD SUCCESSFUL in 28s**
- dist 수동 복사 갱신
- 커밋/푸시: `1df44d0 fix(master): cap plant card size on large screens ...`

## 남은 작업

- 실기기 확인: 가로 모드 전환 시 카드 크기가 세로 모드와 동일하게 유지되는지,
  넓은 화면에서 카드가 셀 가운데 위치하는지
- 태블릿 등 아주 넓은 화면에서 2열 고정 대신 열 수를 늘리는 방안(Adaptive grid)이
  필요하면 별도 요청으로 검토
