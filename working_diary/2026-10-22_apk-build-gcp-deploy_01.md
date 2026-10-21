# 작업 일지 — 2026-10-22 (3차 세션)

## Android Farmer 기능 APK 빌드 + GCP 백엔드 배포·검증

**요구사항:** 2차 세션(Android 이식)에서 만든 farmer 기능 4종(F1 건강등급/F2 해충 CNN/F3 변화 알림/F4 자동 방향 촬영)을 담은 APK를 빌드해 `dist/`에 출력하고, 신규 이벤트 SSE push·camera_mount 정책이 반영된 백엔드를 GCP에 배포.

**커밋 (push):**

| 커밋 | 내용 |
|---|---|
| `0c2d889` | `dist/oojoo-farm-farmer-debug.apk`, `dist/oojoo-farm-master-debug.apk` 재빌드(신규 모델 + 이식 코드 포함) |
| (본 일지) | 세션 로그 |

---

## ① APK 빌드 → dist 출력

- `.\gradlew.bat :slave:app:assembleDebug :master:app:assembleDebug --console=plain --no-daemon` → **BUILD SUCCESSFUL in 2m 24s**
- dist 자동 복사 훅이 동작하지 않아 수동 복사 (스킬 절차대로):
  - `dist/oojoo-farm-farmer-debug.apk` — 23.9MB, 2026-10-22 01:19 갱신
  - `dist/oojoo-farm-master-debug.apk` — 30.5MB, 2026-10-22 01:19 갱신
  - (직전 산출물은 2026-08-27 자였음)

**환경 이슈 해결:** `build-apk-dist` 스킬의 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`는 **이 PC에 존재하지 않음**(Android Studio 미설치). 실제 JDK는 Windows PATH의 **Microsoft OpenJDK 17** (`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`). JAVA_HOME 미설정 상태로 빌드하면 `gradlew`가 PATH의 java를 찾아 정상 동작 → JAVA_HOME 설정 없이 빌드.

## ② 실기기 E2E — 보류 (기기 없음)

- `adb devices` (SDK `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`) → **연결된 기기/에뮬레이터 없음**
- 실 카메라 등급/해충 추론·촬영 방향·알림 수신 확인은 **기기 연결 후로 이월**

## ③ GCP 백엔드 배포

- 로컬 push 선행 완료(`46cad69`) → VM이 GitHub에서 pull
- `git pull` 최신 반영(일지 파일들 포함), `npm install` up-to-date, `sudo systemctl restart oojoo` → **active**
- 헬스체크 `http://34.50.40.104:4000/health` → **200 `{"ok":true,...}`**

**접속 경로 이슈 해결:** 스킬의 `gcloud compute ssh` 대신 순수 OpenSSH 직접 접속(10-20 세션 경로). 이때 Windows OpenSSH는 기본 키(`id_rsa`/`id_ed25519`)만 탐색하므로 gcloud 생성 키를 **명시**해야 한다:

```powershell
ssh -i "$env:USERPROFILE\.ssh\google_compute_engine" wooju-family@34.50.40.104 "<원격 명령>"
```

- 인스턴스 메타데이터 SSH 키 = `wooju-family:ssh-rsa …cklee-gcp` ↔ 로컬 `~/.ssh/google_compute_engine.pub` **일치 확인** (`gcloud compute instances describe … --format="value(metadata.ssh-keys)"`)

## ④ 배포 환경 E2E 검증 (GCP 실서버 대상 시뮬레이션)

`node src/index.js --base http://34.50.40.104:4000 --scenario s3,s4` 실행:

- **[S3] 건강변화 알림 E2E — 통과** (4건 전이 체인 정상) — 배포된 백엔드가 `health_change` 수신 → master SSE push + 알림 센터 집계(plant_name 조인)까지 처리
- **[S4] 자동 가로/세로 촬영 E2E — 4/4 통과** — GCP에 원격 `camera_mount` 정책(landscape/portrait) 설정 → farmer가 정책 반영해 640x480(가로)/480x640(세로) 촬영·업로드, master SSE `photo_ready` 수신

→ 신규 기능이 **GCP 실서버에서 종단간 동작**함을 확인.

---

## 검증 요약

```
✔ APK     : slave + master assembleDebug BUILD SUCCESSFUL → dist 갱신
✔ GCP     : git pull + npm install + systemctl restart → active, /health 200
✔ GCP E2E : 시뮬레이션 S3(변화 알림) + S4(자동 방향 촬영) 통과 (실서버 대상)
✖ 기기 E2E: 연결 기기 없음 → 이월
```

## 남은 작업

- **실기기 E2E** — 기기 연결 후 `dist/oojoo-farm-farmer-debug.apk` 설치, 실 카메라로 등급/해충 추론·촬영 방향·알림 수신 확인 (실물 화면은 합성 분포와 다름 → 진단 확정 라벨 파인튜닝 경로 연결 필요)
- **FCM 푸시** — 현재 앱 내 알림 센터 + SSE. Firebase 프로젝트 설정 필요(별도 대형 작업)
- **루트 `PC` 파일 삭제 여부** — 64B 산출물, 사용자 결정 필요 (이월)

## 특이사항

- 두 스킬(`build-apk-dist`, `deploy-backend-gcp`)의 문서화된 환경 경로(JAVA_HOME, gcloud ssh)가 이 PC 현황과 어긋나 있었음 — 본 일지에 실제 경로 기록. 다음 배포 시 `ssh -i ~/.ssh/google_compute_engine wooju-family@34.50.40.104` 및 JAVA_HOME 미설정으로 진행.
- 배포 검증에 시뮬레이션을 GCP에 직접 실행하는 방식을 채택 — 로컬 백엔드와 동일 코드이므로 실서버 검증이 가능했고, 정책 원격 설정→farm 적용→SSE 수신 전 구간을 실제로 확인했다.
