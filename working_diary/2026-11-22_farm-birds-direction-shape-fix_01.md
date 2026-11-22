# 2026-11-22 — 새 떼 방향/모양 수정 (거꾸로 비행 + 앉아있는 새 문제)

## 요구사항 / 문제 원인

- 사용자 피드백: "새가 거꾸로 날아가고 날아다니는 모양의 새가 아니다."
- 애니메이션 프레임 문제는 아님 — 2026112203(구름 가시화) 빌드부터 구름 등 애니메이션
  정상 동작 확인됨. 이번 건 순수 렌더링 문제 2건:
  1. **거꾸로 비행**: 🐦 이모지는 왼쪽을 바라보는 모양인데 이동 방향은 왼쪽→오른쪽이었음
  2. **모양**: 🐦 이모지는 앉아 있는 새 — 날개를 펄럭이는 나는 새 모양이 아님

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `android/master/.../ui/FilamentViewer.kt` | 새 이동 방향 반전(오른쪽→왼쪽, 구름과 동일) + Canvas 날갯짓 새 실루엣으로 교체 + `drawBird` 헬퍼 추가 |
| `dist/oojoo-farm-master-debug.apk` | 재빌드 (2026112204) |

## 상세 변경

1. **진행 방향**: `bx = (maxW+90) - f*(maxW+180)` — 오른쪽 밖에서 나와 왼쪽 밖으로.
   구름(cloudX)과 같은 방향이라 하늘 흐름이 일관됨.
2. **나는 새 실루엣** (`drawBird`, DrawScope 헬퍼):
   - 몸통: 원 (잉크색 #263238)
   - 날개: 좌우 quadraticBezier 곡선, 끝점이 `sin(flap)`으로 상하 ±4dp 펄럭임
   - 비행 9초 동안 약 9번(1Hz) 날갯짓, 편대 3마리 위상 0/1/2rad 엇갈림 → 각자 다른 박자
3. **빌드 이슈**: `StrokeCap` import 경로 오류(`drawscope` → `androidx.compose.ui.graphics`) — 1회 실패 후 수정, 재빌드 성공

## 검증 결과

- `:master:app:assembleDebug --no-daemon` — **BUILD SUCCESSFUL in 31s**
- dist 수동 복사 갱신
- 커밋/푸시: `373a113 fix(master): flying birds face travel direction with flapping wings ...`

## 남은 작업

- 실기기 확인: 홈 진입 2~5초 내 새 편대 — 진행 방향(오른쪽→왼쪽)과 날갯짓 확인
- 새 크기/색(#263238)이 하늘에서 잘 보이는지, 너무 작으면 scale 조정
