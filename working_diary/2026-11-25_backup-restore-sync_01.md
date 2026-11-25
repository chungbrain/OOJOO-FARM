# 2026-11-25 백업 기준 현재 디렉토리 동기화 (backup-restore-sync)

## 요구사항 / 문제 원인

- `OOJOO-FARM-backup-20261125-1501/OOJOO-FARM` 백업(당일 15:01 스냅샷)을 기준으로
  현재 작업 디렉토리(`C:\Users\cklee\dev\OOJOO-FARM`)의 데이터를 최신 상태로 갱신하고,
  필요 시 빌드 후 GitHub에 push.
- 원인: 현재 디렉토리가 백업 대비 **12 커밋 뒤처진 상태**(HEAD `45aa559`, 빌드 2026112503)였고,
  gitignore된 작업 데이터(ml 학습 데이터셋 PNG 등) 일부가 유실되어 있었음.

## 백업 대비 현재 디렉토리 상태 (작업 전)

| 항목 | 상태 |
|---|---|
| 공통 히스토리 | merge-base = 현재 HEAD(`45aa559`) — fast-forward 가능 |
| 백업 HEAD | `7b63060` (빌드 2026112506, 이웃탭 위치시스템 · 페어링 코드 표시 · neighbor feed 지역 기본값 포함) |
| 백업 미커밋 변경 | `android/gradlew`, `scripts/build-dist.sh` 파일 모드(755→644)뿐 — 내용 변화 없음 |
| 유실된 작업 데이터 | ml/plant_health/datasets PNG 약 8,500장 외 ml 보조 스크립트·prototype·simulation 등 |

## 변경 내역

### 1) git fast-forward (트래킹 파일 — 15개 파일, +479/−21)

| 파일 | 내용 |
|---|---|
| `android/master/.../data/AppLocale.kt` | 로케일 문자열 추가 |
| `android/master/.../model/Models.kt` | 모델 필드 확장 |
| `android/master/.../network/ApiService.kt` | API 연동 |
| `android/master/.../ui/CommunityScreen.kt` | neighbor feed — 농장 기본 지역 + 내 지역 추가 검색 |
| `android/master/.../ui/FarmerListScreen.kt` | 파머 카드에 페어링 코드 표시 |
| `android/master/.../ui/PlantListScreen.kt`, `Theme.kt` | 식물 카드 공유/판매 게시글에 파머 위치 사용 |
| `values/strings.xml`, `values-en/strings.xml` | 신규 문자열 |
| `backend/src/routes/pairing.js` | 슬레이브 목록에 각 파머 페어링 코드 노출 |
| `backend/test/community_radius.test.js` (+84) | 커뮤니티 반경 검색 계약 테스트 (신규) |
| `backend/test/pairing_code.test.js` (+82) | 페어링 코드 노출 계약 테스트 (신규) |
| `dist/oojoo-farm-farmer-debug.apk` | 2026112506 빌드로 교체 |
| `dist/oojoo-farm-master-debug.apk` | 2026112506 빌드로 교체 (19.6MB→19.0MB) |
| `scripts/build-dist.sh` (+53) | assembleDebug 후 dist 복사 스크립트 (신규) |

### 2) 유실 작업 데이터 복사 (gitignore 대상 — 9,388파일, 약 980MB)

- `ml/plant_health/datasets/**` — 종별 학습 PNG(sweet_potato, zucchini 등 전체)
- ml 보조 스크립트(`fetch_curated.py`, `train_all.py`, `label_web/` 등) 및 metrics
- `prototype/`, `assets/`, `simulation/`, `tools/`, `scripts/emulator/` 등 백업에만 존재하던 작업 파일
- 제외: `.git`, `android/**/build`, `android/.gradle`(재생성 캐시), `dist`(git으로 복원됨),
  현재 디렉토리의 런타임 데이터(`backend/data` 사진·영상·DB, 로그)는 백업 것으로 덮어쓰지 않음

## 동작 흐름

1. 두 디렉토리 전수 비교(파일 목록 + MD5) → 백업이 12 커밓 선행 확인, 차이의 대부분이
   빌드 산출물/줄바꿈 노이즈/유실된 gitignore 데이터임을 확인
2. 백업 저장소를 임시 remote로 fetch → `git merge --ff-only FETCH_HEAD`로 `45aa559` → `7b63060` 갱신
3. 현재 디렉토리에 없는 파일만 선별 복사(9,388파일) — 기존 런타임 데이터는 미변경
4. `git status`로 트래킹 파일 무결성 확인 (깨끗 — `?? PC`, `?? requirements/` 만 로컬 미추적)

## 검증 결과

- **빌드**: 불필요 — `7b63060` 커밋에 2026112506 빌드 APK가 이미 포함,
  현재 `dist/` APK가 백업과 MD5 완전 일치(farmer/master 모두 `match=True`)
- **push**: `git push origin main` 성공 — `45aa5592..7b630605 main -> main` (12 커밋 게시)
- **테스트**: 백업 시점에 pairing_code / community_radius 계약 테스트 커밋 포함(`b78b772`, `b634412` 계열)

## 남은 작업

- `PC`, `requirements/` 미추적 항목의 처리(커밋 또는 gitignore 등록) 여부 결정
- 백업의 미커밋 변경(파일 모드 755→644)은 Windows 복사 아티팩트로 판단해 의도적으로 미반영
- GCP 백엔드(34.50.40.104)에 pairing.js 변경사항 반영 필요 시 `deploy-backend-gcp` 절차 수행
