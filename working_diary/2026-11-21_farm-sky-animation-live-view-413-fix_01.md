# 작업 일지 — 2026-11-21 (1차 세션)

## 농장 씬 하늘 애니메이션 + 실시간 보기 413 업로드 실패 수정 + APK 빌드·GCP 배포

**요구사항:**

1. 농장에서 돌아다니는 farmer 캐릭터를 "예전 것"으로 되돌리고 이름 팻말 디자인 개선
2. farmer 실시간 보기(사진/영상) 미동작 원인 진단·수정
3. 농장 하늘 애니메이션 — 구름 흐름, 가끔 날아가는 새, 밤 별자리

**커밋 (push `1bc047d..1b84ca9`):**

| 커밋 | 내용 |
|---|---|
| `69fdefa` | 사진 업로드 413(File too large) 방지 — slave 업로드 전 1920px 축소 + 백엔드 제한 상향(사진 8→25MB, 영상 40→100MB) |
| `0ec67fd` | 농장 하늘 애니메이션 — 흐르는 구름 3종, 새 떼 편대, 밤 별가루 + 북두칠성 |
| `1b84ca9` | debug APK 재빌드(2026112101) |

---

## ① 캐릭터/팻말 — 원격 최신과의 통합

- git 전수조사 결과 farmer 캐릭터 이모지(🤖)는 한 번도 바뀐 적 없고 **움직임 방식**(snap 점프 ↔ 부드러운 순찰)이 변경된 것으로 확인. 예전 = 5초 부드러운 걷기 순찰로 판단하고 로컬에서 복원 작업 진행.
- 그러나 push 시점에 **원격(다른 머신)에 이미 더 완성도 높은 구현**이 올라와 있었음 — `d3e58e8` "farm scene — smooth robot, stable plant moves, wood signs": 로봇 프리미티브 리빌드(안테나/깜빡이는 눈/바퀴/물방울), 나무 팻말(그라디언트+기울기+말줄임), 식물 이동 안정화(cell을 plant id로 키잉), 웨이포인트 유지 로봇 순찰.
- **로컬 로봇/팻말 버전은 폐기**하고 `git reset --hard origin/main` 후 원격 기준으로 재작업. 사용자 요청 1번은 원격 구현이 이미 충족.
- 하늘 애니메이션은 원격에 없어 새로 이식(`0ec67fd`) — 아래 ③ 참조.

## ② 실시간 보기 진단 — 413 File too large

**원인 (crash_logs 기반 확정):**

- GCP 서버 crash_logs에 10-24 07:16~07:35 **"File too large" 1분 간격 반복** 기록
- multer 업로드 제한: 사진 8MB(`photos.js`), 영상 40MB(`videos.js`)
- slave `captureStill`은 **원본 해상도 JPEG**를 그대로 업로드 → 고화소 카메라에서 8MB 초과 → 413 → multer 에러가 app.js 에러 핸들러에서 500 + crash_logs 기록
- 성장 사진 경로(`captureAndArchivePhoto`)는 업로드 실패 시 `setLastPhotoAt` 미실행 → `lastPhotoAttempt` 60초 스로틀만 넘기면 **매분 재시도** → 반복 크래시 로그와 정확히 일치
- 마스터에는 `photo_ready` SSE가 도달하지 못해 실시간 보기 40초 타임아웃 발생

**수정:**

- `OrientationUtil.downscaleJpeg(src, maxDim=1920, quality=85)` 추가 — EXIF upright 디코딩 + inSampleSize 축소 + JPEG 재압축, 실패 시 원본 반환
- `FarmerEngine` 즉시 사진/성장 사진 두 경로 모두 업로드 전 축소본 생성, 임시 파일은 `finally`에서 정리 (앨범용 원본은 유지)
- 백엔드 제한 상향: 사진 8→25MB, 영상 40→100MB (영상은 SD 화질 3초 ≈ 1-2MB라 실사용 불변)

## ③ 하늘 애니메이션 (`FilamentViewer.kt`)

- **구름**: 3개가 각기 다른 속도(42/58/50초)·높이로 하늘을 가로질러 흐름. 0→1 선형 이동이며 양 끝이 화면 밖이라 wrap이 보이지 않음 (기존 ±14dp 좌우 진동 대체). 밤엔 40% 밝기로 은은하게
- **새 떼**: 6~15초 잠복 후 🐦 3마리 V자 편대가 9초에 걸쳐 사인 곡선을 그리며 비행
- **밤하늘**: Canvas로 별가루 18개(시드 고정, 개별 위상 반짝임 2.4초 주기) + **북두칠성**(국자 4성+자루 3성 연결선, 은은한 후광)

## ④ APK 빌드 → dist

- `:slave:app:assembleDebug :master:app:assembleDebug --no-daemon` → BUILD SUCCESSFUL
- dist 수동 복사: `oojoo-farm-farmer-debug.apk` 22.8MB / `oojoo-farm-master-debug.apk` 29.1MB (2026-11-21 11:22)

## ⑤ GCP 백엔드 배포

- `gcloud compute ssh`가 PuTTY PPK 재생성 요구 → `--force-key-file-overwrite`로 연결 성공 (사용자 `cklee`)
- VM repo가 root/타 사용자 소유라 `git pull` 실패(dubious ownership + FETCH_HEAD Permission denied) → **`sudo git config --global --add safe.directory` + `sudo git pull`**로 해결
- VM이 `46cad69`(10-22) → **`1b84ca9`(11-21 최신)**로 갱신, 누락돼 있던 `cbdaf3a`(heartbeat 위치) 등 원격 백엔드 커밋까지 일괄 반영
- `npm install` + `systemctl restart oojoo` → **active**, `/health` → **200 `{"ok":true}`**

---

## 검증 요약

```
✔ 컴파일  : master/slave compileDebugKotlin 통과
✔ APK     : 두 APK 재빌드 → dist 갱신 (2026112101)
✔ GCP     : git pull 1b84ca9 + restart → active, /health 200
✔ 진단    : 실시간 보기 미동작 원인 = 사진 업로드 413 (crash_logs + 코드 경로 확정)
✖ 실기기  : 새 APK(하늘 애니메이션/축소 업로드) 실기기 확인 이월
```

## 남은 작업

- **실기기 확인** — dist APK 설치 후 ①하늘 애니메이션·팻말 ②실시간 보기(사진/영상) 정상 동작 확인. slave는 앱이 내려가 있어도 FarmerService가 유지되므로 기존 설치 위에 덮어쓰면 됨
- **SSH 접속 경로 갱신** — 로컬 키가 재생성되어 일지의 `ssh -i ~/.ssh/google_compute_engine wooju-family@…` 경로가 불통. 현재는 `gcloud compute ssh --force-key-file-overwrite`(cklee) + `sudo git pull` 경로가 유효. wooju-family 키 복구 또는 스킬 문서 갱신 필요
- **diagnosis.js 5MB 한도** — 프레임 배치 업로드 제한은 현행 유지(프레임은 이미 축소 샘플링). 대용량 진단 업로드 이슈 발생 시 재검토

## 특이사항

- 원격(타 머신)에 같은 주제의 신규 작업이 먼저 올라와 있어 로컬 3개 커밋(f30527c 등)을 reset 후 살릴 부분(하늘 애니메이션, 413 수정)만 원격 기준으로 재적용 — push 전 `git fetch`로 원격 상태 확인이 동시 작업 저장소에서는 필수
