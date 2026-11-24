# 2026-11-24 — GCP 백엔드 재배포 + 에뮬레이터 APK 검증

## 요구사항

- APK 리빌드 일지에 남은 작업 2건 즉시 수행:
  1. 새 APK(2026112401) 기기/에뮬레이터 설치·동작 확인
  2. 신규 백엔드 라우트(RoI, 썸네일, 커뮤니티) GCP VM 재배포

## 1. GCP 백엔드 재배포

### 문제 원인

- 첫 배포 시도에서 VM의 `git pull`이 **Permission denied** 실패
  (`error: cannot open '.git/FETCH_HEAD'`) → 서비스는 구 코드(`fd49bcf`)로
  active 상태로만 보였음.
- 원인: `/opt/oojoo/repo` 소유자는 `wooju-family`, SSH 접속 계정은 `cklee` —
  쓰기 권한 불일치.

### 해결

- systemd 서비스(`oojoo.service`)가 `User=wooju-family`로 실행 중임을 확인.
- `sudo -u wooju-family git pull && sudo -u wooju-family npm install`로
  소유자 계정과 일치시켜 재배포 → **성공**.
- VM repo: `fd49bcf` → **`f39db5b`**(최신), 서비스 재시작 후 `active`.

### 헬스체크

```
GET http://34.50.40.104:4000/health → {"ok":true,"service":"oojoo-farm"}
```

## 2. 에뮬레이터 APK 설치·검증

- AVD `oojoo_design` 부팅 → `emulator-5554`
- `adb install -r` 로 master/farmer APK 설치 → 둘 다
  `versionName=1.0.2026112401` 확인
- master 앱 실행 → **정상 렌더링**: 농장 씬, 날씨 카드(💧 77% · 🌧 0mm · -4°C),
  식물 카드 — 날씨 표시는 GCP 백엔드(`34.50.40.104:4000`) 연결 성공 증거
- 프로세스 생존(pid 3436), logcat FATAL/AndroidRuntime 크래시 없음

## 3. 백엔드 신규 코드 테스트

- 신규 3종 파일 실행 → **13/13 통과**
  - `plant_roi.test.js` (5): 설정/반환/병행/삭제/무효값·경계값
  - `photo_album.test.js` (4): 썸네일 업로드·서빙, photo-only 호환,
    farmer→식물 조인, LIMIT 300
  - `community_plant.test.js` (4): 식물카드 게시물 작성/조인, 400 처리,
    growth-story 영상 첨부, 기존 호환
- `app.test.js`의 "SIGTERM graceful shutdown" 1건 실패 — 이번 동기화에서 해당
  파일은 변경되지 않았으므로 **기존 Windows 환경 이슈**(SIGTERM 시맨틱 차이),
  신규 코드 영향 아님.

## 검증 결과 요약

| 항목 | 결과 |
|---|---|
| VM repo 커밋 | `f39db5b` (최신) ✓ |
| oojoo.service | active ✓ |
| /health | `{"ok":true}` ✓ |
| APK 설치 버전 | master/slave 모두 2026112401 ✓ |
| master 앱 실행 | 농장 씬 + 백엔드 연계 날씨 렌더링, 크래시 없음 ✓ |
| 신규 백엔드 테스트 | 13/13 통과 ✓ |

## 남은 작업

- RoI 편집기·앨범 UI 상세 동작은 farmer(실기기) 페어링 + 사진 데이터가
  필요 — 실기기에서 최종 확인 권장
- `app.test.js` SIGTERM 테스트 Windows 비호환 — 필요시 플랫폼 분기 처리
