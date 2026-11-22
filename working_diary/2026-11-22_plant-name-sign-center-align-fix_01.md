# 2026-11-22 — 농장 씬 이름팻말 가운데 정렬 + 아래 잘림 수정

## 요구사항 / 문제 원인

- **요구**: 나의 농장 씬에서 식물에 붙는 나무 이름팻말의 이름을 상하좌우 가운데 정렬로 설정.
- **원인**: 팻말 몸통 높이가 `17dp`로 고정. 시스템 글자 크기(폰트 확대, fontScale)가 적용된
  기기에서 8sp 이름 텍스트의 세로 폭(라인 높이)이 17dp를 넘으면 텍스트가 위에서부터 채워지고
  **아래쪽이 잘려 보임** → 가운데 정렬도 눈에 제대로 보이지 않았음.

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `android/master/.../ui/FilamentViewer.kt` | 팻말 몸통 높이를 fontScale 반영 계산으로 변경, 텍스트 중앙 정렬 명시, signH(전체 높이=줄기7+몸통) 오프셋 연동 |

## 상세 변경

1. **가운데 정렬 (상하좌우)**
   - `WoodSign`의 `Box`가 `contentAlignment = Alignment.Center` 유지 (상하·좌우 모두)
   - `Text`에 `textAlign = TextAlign.Center` 추가 — 가로 정렬 명시적 보장
2. **아래 잘림 해결**
   - 팻말 몸통 높이: `17.dp` 고정 → `17dp × fontScale`(`LocalDensity.current.fontScale`)
   - 몸통 높이를 `WoodSign(plant.name, signBodyH)` 파라미터로 전달 (호출부와 단일 소스)
   - 팻말 전체 높이 `signH = signBodyH + 7f`(줄기)로 계산해 배치 오프셋(`onTop` 여부별
     y 위치)도 함께 연동 → 글자 크기가 커져도 팻말이 원/카드와 어긋나지 않음
3. **import 추가**: `LocalDensity`, `TextAlign`

## 동작 흐름

- 홈 화면 → 나의 농장 카드 → `FarmSceneView` → 식물 cell마다 원(흙) 아래/위에 나무 팻말 렌더링
- 맨 아래 행(row 3)은 카드 하단으로 잘리지 않도록 기존처럼 원 위쪽에 배치 (변경 없음)
- fontScale=1.0 기기에서는 기존과 동일한 크기(17dp) — 시각적 회귀 없음

## 검증 결과

- `..\gradlew.bat :master:app:compileDebugKotlin` (android/master) — **빌드 성공**
- 커밋: `918c3b2 fix(master): center plant name sign text and prevent bottom clipping at large font scale`
- 푸시: `origin/main` 반영 완료 (5409c4d..918c3b2)

## 남은 작업

- 기기/에뮬레이터에서 실제 화면 확인 (특히 시스템 글자 크기 큰 설정에서 이름이 온전히 보이는지)
- 필요 시 APK 빌드(`build-apk-dist`) 후 배포 확인
