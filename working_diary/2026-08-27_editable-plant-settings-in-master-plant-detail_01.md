# 작업 일지 — 2026-08-27

## Master 식물 상세에서 식물 정보 수정 (commit `a127316`)

**요구사항:**
- "내 식물" 페이지 → "식물 상세" 카드 진입 시 식물의 설정/값을 수정 가능하게 한다

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/master/.../ui/PlantDetailScreen.kt` | 식물 정보 카드 우측 상단 ✏️ 버튼 → 편집 폼 카드로 전환. 이름/작물 종류(인기 작물 드롭다운+직접 입력)/식재일/담당 Farmer/생장 단계 수정. 저장 시 기존 `PUT /api/plants/plant/:id` 호출 후 자동 새로고침. `PlantEditCard` composable 신규, ViewModel에 edit 상태 + `startEdit()`/`saveEdit()` 추가, Farmer 목록 로드 추가 |
| `android/master/.../data/AppLocale.kt` | `plantEdit`, `plantEditTitle` 문자열 노출 추가 |
| `android/master/.../ui/LocalizedMessages.kt` | "수정되었습니다" → "Updated" 영문 매핑 추가 |
| `values/strings.xml`, `values-en/strings.xml` | `plant_edit`, `plant_edit_title` 신규 |
| `dist/oojoo-farm-master-debug.apk` | 재빌드 반영 (26.6MB) |

**동작 흐름:**
1. 내 식물 → 식물 카드 탭 → 식물 상세
2. 카드 우측 ✏️ 버튼 → 편집 폼 표시 (작물 이모지 실시간 미리보기)
3. 저장 → 백엔드 PUT → 카드가 새 값으로 갱신, 목록 복귀 시에도 자동 갱신 (ON_RESUME refresh)
4. Farmer 라인은 slave ID 대신 Farmer 이름 표시하도록 개선

**백엔드:** 변경 없음 — 기존 `PUT /api/plants/plant/:id` 재사용 (`encodeDefaults=true`로 null 배정 해제도 정상 전송)

**검증:**
- `:master:app:compileDebugKotlin` ✅ (1차 실패: `PlantEditCard`에 `@OptIn(ExperimentalMaterial3Api::class)` 누락 → 추가 후 통과)
- `:master:app:assembleDebug` ✅ → dist APK 갱신
- JBR 경로(`C:\Program Files\Android\Android Studio\jbr`)가 이 PC에 없어 Microsoft OpenJDK 17로 빌드 — SKILL.md의 환경 정보가 실제 PC와 다름 (이후 확인 필요)

**남은 작업:**
- 실기기에서 편집 폼 UX 확인
