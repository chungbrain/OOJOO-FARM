# OOJOO-FARM — Claude Code 작업 지침

사용자와는 한국어로 대화한다.

## 먼저 볼 곳

- 프로젝트 구조·핵심 파일: 스킬 `oojoo-project-map`
- 작업 지식: `.claude/knowledge/` (OKF v0.3). 세션 시작 때 목록이 들어온다. 빌드·배포·커밋·기존 기능 수정 전에 관련 개념만 골라 읽는다.
- 스킬에 적힌 경로(`C:\OOJOO-FARM`, Android Studio `jbr`)는 다른 PC 기준일 수 있다. PC별 값은 `.claude/knowledge/infra/` 의 환경 개념을 따른다.

## 스킬 (`.claude/skills/`)

| 스킬 | 쓰는 때 |
|---|---|
| `build-apk-dist` | 안드로이드 APK 빌드 → `dist/` |
| `deploy-backend-gcp` | GCP VM 백엔드 배포 |
| `commit-push-diary` | 커밋·푸시·working_diary |
| `sync-from-github` | 원격 main으로 강제 동기화 |
| `oojoo-project-map` | 코드 탐색 전 구조 파악 |

## 턴 종료 훅 (`.claude/settings.json`)

- 턴이 처음 끝날 때 Stop 훅이 OKF 갱신 점검을 요청한다. 규칙은 `.claude/okf/GUIDE.md`, 기록할 게 없으면 「기록할 새 지식 없음」 한 줄로 끝낸다.
- 두 번째 종료에서 남은 변경을 모두 `chore(auto): end-of-turn commit …` 으로 커밋한다(push 없음). 의미 있는 커밋(feat/build/docs)은 턴이 끝나기 전에 직접 나눠 한다.

## 관례

- 커밋: `feat:`/`fix:`/`docs:`/`build:`/`chore:` + 영어 요약, 논리 단위로 분할. 커밋·푸시를 요청받으면 `working_diary/` 일지까지 쓴다(규칙 `working_diary/README.md`).
- push는 사용자가 요청할 때만 한다.
- git 사용자가 설정되지 않은 PC에서는 전역 설정을 바꾸지 말고 `git -c user.name=chungbrain -c user.email=chungbrain@users.noreply.github.com commit …` 으로 커밋한다.
- 문자열은 `values/` 와 `values-en/` 에 함께 추가하고 `node scripts/verify-locales.mjs`, 코드 구조가 바뀌면 `node scripts/code-graph.mjs` 후 `--check`.
