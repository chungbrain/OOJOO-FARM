# 2026-11-27 턴 종료 훅(자동 커밋) + OKF 지식 번들 (turn-end-hooks-okf-knowledge)

## 요구사항

1. Claude Code의 한 턴이 끝날 때마다 git commit 하는 훅
2. 턴이 끝날 때마다 [Open Knowledge Format(OKF)](https://github.com/open-knowledge-format/open-knowledge-format) 형태로 지식을 관리 — 모든 내용을 기억할 필요는 없음

## 설계

| 항목 | 결정 |
|---|---|
| 지식 위치 | 저장소 `knowledge/` = OKF v0.3 번들(루트 `index.md` 에 `okf_version: "0.3"`). git으로 이력 관리 — OKF 권장 배포 방식 |
| 기록 기준 | 다음 세션에 쓸모 있는 것만: 환경·경로 특이점, 통한 절차, 인프라 사실, 설계 결정과 이유, 기능 계약, 함정과 해결. 코드·README·일지에 이미 있는 내용은 링크만, 비밀·개인정보 제외 |
| 턴 종료 흐름 | Stop 훅이 턴의 첫 종료를 한 번 막고 번들 갱신 점검을 요청 → 두 번째 종료(`stop_hook_active`)에서 자동 커밋. 코드와 지식이 커밋 하나로 들어감 |
| 자동 커밋 | `git add -A` → `chore(auto): end-of-turn commit - <영역> (<시각>)`. push 안 함. merge/rebase 중·detached HEAD면 건너뜀, `.env`·`settings.local.json`·50MB 초과 파일 제외 |
| 세션 시작 | SessionStart 훅이 `knowledge/` 의 index 목록을 컨텍스트에 넣어 필요한 개념만 읽게 함 |
| 훅 범위 | 개인 설정이라 `.claude/settings.local.json`, `.claude/hooks/`, `.claude/okf/` 는 gitignore — 이 PC에만 있음 |
| 검증 | OKF 저장소 `skills/okf-generate/scripts/validate.py`(Apache-2.0, 527e81a) 사본을 `.claude/okf/.venv`(PyYAML)로 실행 |

## 변경 내역

| 파일 | 내용 |
|---|---|
| `.gitignore` | Claude Code 개인 설정(훅·OKF 도구) 제외 |
| `knowledge/index.md`, `log.md` | 번들 메타데이터, 변경 기록 |
| `knowledge/infra/` | `gcp-backend-vm`(Server), `windows-dev-pc`(Environment), `claude-code-hooks`(Tooling) |
| `knowledge/playbooks/` | `build-android-apk`, `deploy-backend-gcp`, `commit-and-diary` |
| `knowledge/features/` | `community-post-media` (이웃 글 사진·동영상 첨부 계약과 설계 결정) |
| 로컬 전용(커밋 안 됨) | `.claude/settings.local.json`, `.claude/hooks/{turn-end,auto-commit,okf-session-start}.sh`, `.claude/okf/{GUIDE.md,validate.sh,validate.py}` |

## 검증 결과

- 자동 커밋 스크립트: 임시 저장소에서 변경 없음/추가·수정·삭제/`.env`·51MB 파일 제외/한글 파일명/merge 중 건너뜀/git 사용자 미설정 시 대체 작성자 확인
- 실제 동작: `43b7b01`(.gitignore), `8b36476`(knowledge 번들) 이 턴 종료 시 자동 커밋됨, OKF 점검 요청도 턴 종료 시 실행됨
- `bash .claude/okf/validate.sh` → 12 files, 0 errors, 0 warnings (conformant)
- 첫 실행에서 발견: 점검 후 빈 응답으로 끝내면 Claude Code가 보이는 응답을 다시 요구 → 기록할 게 없으면 「기록할 새 지식 없음」 한 줄로 답하도록 수정

## 참고·주의

- `.opencode/skills/*` 의 경로(`C:\OOJOO-FARM`, Android Studio `jbr`)는 다른 PC 기준 — 이 PC 값은 `knowledge/infra/windows-dev-pc.md`
- 턴마다 짧은 점검 단계가 하나 더 붙음(기록할 게 없으면 바로 끝남). 훅을 보거나 끄려면 `/hooks`

## 남은 작업

- 다음 세션에서 SessionStart 목록 주입이 실제로 들어오는지 확인
- 번들이 커지면 index 주입 분량(현재 8KB 상한) 조정
