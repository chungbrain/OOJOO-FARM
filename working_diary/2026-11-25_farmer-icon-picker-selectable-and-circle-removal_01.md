# 2026-11-25 — farmer 아이콘 원 제거 + 아이콘 선택/변경 기능

## 요구사항

1. farmer의 아이콘 주변에 원(배경/테두리)이 없도록 한다.
2. farmer의 아이콘을 사용자가 **선택하고 변경**할 수 있도록 한다.
3. 빌드 + 에뮬레이터 재배포 + 푸시.

## 문제 원인 / 배경

- 직전 커밋(c38654f)에서 farmer 색 틴트를 없애며 흰 원 + 잉크 테두리를
  남겼는데, 원 자체도 제거 요청 — 아이콘 이모지만 표시하도록.
- 아이콘은 `farmerIconFor(slaveId)`가 ID 해시로 자동 배정했으나,
  사용자가 직접 고를 수 있는 수단이 없었다.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ui/FarmerListScreen.kt` — 목록 카드 | 아이콘 원 Box 제거 → 이모지 텍스트만(30sp). **아이콘 탭 → 선택 다이얼로그** 오픈 |
| `ui/FarmerListScreen.kt` — IconPickerDialog 신규 | 4열×2행 그리드로 팔레트 8종 표시, 현재 아이콘 녹색 강조, 탭하면 즉시 선택. "자동 지정" 버튼 → 선택 해제(ID 해시 기본 복원) |
| `ui/FilamentViewer.kt` — FarmerFigure | 원 배경/테두리 Box 제거 → 아이콘 텍스트만(28sp). 반전/기울기/커짐 애니메이션은 아이콘에 직접 적용 |
| `ui/FilamentViewer.kt` — 아이콘 로직 | `farmerIconFor`를 **@Composable**로 변경: 선택 아이콘(`FarmerIconStore`) > 기본 아이콘(`defaultFarmerIcon`, ID 해시). `FarmerIconPalette`를 공개해 다이얼로그에서 재사용 |
| `ui/FilamentViewer.kt` — FarmerIconStore 신규 | `mutableStateMapOf` 오버라이드 캐시 — 선택 즉시 모든 화면(농장 씬/식물카드/사진첩/목록)에 반영. `Prefs(farmerIcons)`로 영속화 |
| `data/Prefs.kt` | `farmerIcons` 키 추가 — `farmerIcons()/setFarmerIcon()/removeFarmerIcon()` (JSON 오브젝트, farmPlantCells와 동일 패턴) |
| `res/values/strings.xml`, `values-en/strings.xml` | `icon_change_title/hint`, `icon_auto`, `icon_changed` 신규 (한/영) |
| `data/AppLocale.kt` | `iconChangeTitle/iconChangeHint/iconAuto/iconChanged` 속성 추가 |
| `dist/oojoo-farm-master-debug.apk` | 신규 빌드 사본 (2026112502) |

## 동작 흐름

farmer 목록 카드에서 아이콘 탭 → IconPickerDialog → 팔레트에서 아이콘 탭 →
`FarmerIconStore.select()` (SnapshotState + Prefs 저장) → 다이얼로그 닫힘과
동시에 목록 아이콘 갱신. 농장 씬 figure/식물카드 pill/사진첩 헤더도
같은 저장소를 읽으므로 즉시 같은 아이콘으로 전환.
"자동 지정"은 오버라이드 삭제 → ID 해시 기준 기본 아이콘 복원.

## 검증 결과 (에뮬레이터 oojoo_design, 2026112502 설치)

- `:master:app:compileDebugKotlin` — BUILD SUCCESSFUL
- `:master:app:assembleDebug` — BUILD SUCCESSFUL, dist 복사 확인
- adb install -r — **Success**
- 홈(농장 씬): farmer figure가 👾 **원 없이** 렌더링 ✓
- farmer 목록: 두 farmer(으라찻차 🐥 / 기충이 👾) 아이콘만 표시, 원 없음 ✓
- 아이콘 탭 → "Farmer 아이콘 선택 — 'Farmer-으라찻차'" 다이얼로그,
  8종 아이콘 + 취소/자동 지정 표시 ✓
- 🦊 선택 → 목록 즉시 🐥→🦊 갱신 ✓
- force-stop → 재시작 → farmer 탭 → **🦊 유지** (Prefs 영속화 확인) ✓
- uiautomator dump 텍스트 검증 (스크린샷: 이 세션 모델은 이미지 확인 불가)

## 남은 작업

- 선택 아이콘은 현재 기기 로컬 저장 — 가족 공유 기기/재설치 시 유지되도록
  백엔드(slaves 테이블 icon 컬럼 등) 동기화 요청 시 추가
- 에뮬레이터(`-no-window` 헤드리스)는 빌드 검증용으로 계속 실행 중
