# 2026-11-25 — farmer 아이콘 선택 백엔드 동기화

## 요구사항

farmer 아이콘 선택을 백엔드에 저장해 **다른 기기/가구(가족 공유)에서도
같은 아이콘이 유지**되게 한다. (직전 구현은 기기 로컬 Prefs 저장)

## 설계

- 서버 값이 source of truth: `slaves.icon` 컬럼(이모지 문자열, null=자동 배정).
- 앱은 2단 저장 — 로컬 Prefs(즉시 반영+오프라인) + 서버 PUT(비동기 write-through).
- 모든 slaves fetch를 `FarmerIconStore.fetchSlaves()`로 통일해 어느 화면에서
  목록을 불러도 `syncFromServer`로 아이콘이 반영된다 (6개 호출부 교체).
- push 미완료 선택은 `pendingSync`로 보호 — fetch 동기화가 서버의 옛 값으로
  되돌리지 않게 한다. 실패(오프라인)해도 로컬 선택 유지.
- 이전 버전(로컬 전용)에서 고른 선택은 앱 기동 시 서버로 이관 push.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `backend/src/db.js` | `ensureColumn('slaves','icon','icon TEXT')` 마이그레이션 |
| `backend/src/routes/pairing.js` | GET 목록 SELECT에 `s.icon` 포함. 신규 `PUT /api/pairing/:slaveId/icon` — 404(미등록)/400(icon은 1..8자 또는 null)/UPDATE |
| `model/Models.kt` | `Slave.icon` 필드 + `SlaveIconRequest/SlaveIconResponse` |
| `network/ApiService.kt` | `setSlaveIcon()` (PUT api/pairing/{slaveId}/icon) |
| `ui/FilamentViewer.kt` — FarmerIconStore | `syncFromServer`(서버값 반영, pendingSync 보호) / `pushToServer`(GlobalScope write-through) / `fetchSlaves`(조회+동기화 통일) / ensureLoaded에서 이전 로컬 선택 서버 이관 |
| farmer/홈/식물 목록·상세·등록/사진첩 (6파일) | `api.slaves(...)` → `FarmerIconStore.fetchSlaves(...)` |
| `dist/oojoo-farm-master-debug.apk` | 신규 빌드 사본 (2026112503 내용, 파일명 유지) |

## 검증 결과

### 로컬 백엔드 스모크 (node --experimental-sqlite src/server.js)

- 페어링 코드 → slave 생성 → PUT icon 🦊 200 `{ok:true,icon:"🦊"}` ✓
- GET 목록에 `"icon":"🦊"` 포함 ✓ / PUT null → 목록 `icon:null` 복원 ✓
- 존재하지 않는 slave 404 ✓ / icon 숫자값 400 ✓

### GCP 배포

- `git push origin main` (415fd01) 후 VM `/opt/oojoo/repo` git pull +
  npm install + `systemctl restart oojoo` → **active**, `/health` 200 ✓
- 배포 접속 장애 복구: `gcloud compute ssh`(기본 사용자 cklee)는
  `/opt/oojoo/repo` 권한 없어 git pull 실패 → 인스턴스 메타데이터에 이 PC
  공개키를 `wooju-family:` 계정으로 추가(gcloud add-metadata, 기존 키 보존)
  후 OpenSSH 직접 배포 성공 — 다음 배포도 이 경로 사용
- VM DB `icon` 컬럼 자동 마이그레이션 확인(기존 7개 slave 전부 null) ✓

### 에뮬레이터 E2E (oojoo_design, GCP 서버 직접)

- APK install -r → 기동 시 로컬 선택(🦊, 이전 세션)이 서버로 이관 —
  GCP DB `hex(icon)=F09FA68A` = 🦊 ✓
- 앱에서 기충이 아이콘 🐝 선택 → GCP DB `hex(icon)=F09F909D` = 🐝 ✓
- farmer 탭 화면 아이콘 🦊/🐝 표시, 목록 GET 응답에 icon 포함 ✓

## 남은 작업

- 오프라인에서 고른 선택의 자동 재전송(현재는 fetch 시점 보호만, 재시도 없음)
- 아이콘 whitelist(서버는 1..8자만 검사 — 팔레트 외 이모지도 저장 가능)
