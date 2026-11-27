# .claude — OOJOO-FARM 에이전트 개발 환경

OOJOO-FARM 개발에 쓰는 AI 에이전트 환경(지침, 스킬, 훅, 지식 번들)을 한곳에 모은 폴더다. Claude Code 기준이며, 스킬은 OpenCode도 읽는다.

| 경로 | 내용 | 읽는 쪽 |
|---|---|---|
| `CLAUDE.md` | 프로젝트 작업 지침 | Claude Code (세션마다 자동 로드) |
| `settings.json` | 프로젝트 훅 설정 (SessionStart, Stop) | Claude Code |
| `hooks/` | `turn-end.sh`(턴 종료 분기), `auto-commit.sh`(자동 커밋), `okf-session-start.sh`(지식 목록 주입) | `settings.json` 의 훅 |
| `skills/` | 작업 절차 스킬 5개. 2026-08-23 `.opencode/skills` 에 만든 것을 2026-11-27 이곳으로 옮김 | Claude Code, OpenCode(`.claude/skills` 호환 경로) |
| `knowledge/` | 프로젝트 지식 번들 ([OKF v0.3](https://github.com/open-knowledge-format/open-knowledge-format/blob/main/SPEC.md)) — 환경, 절차, 서버, 기능 계약 | 사람, 에이전트 |
| `okf/` | 번들 관리 규칙 `GUIDE.md`, 검증기 `validate.sh` + `validate.py`(OKF 저장소 사본, Apache-2.0, `LICENSE-okf.txt`) | 에이전트 |

## 턴이 끝날 때

1. Stop 훅이 한 번 막고 이번 턴에서 기록할 지식이 있는지 점검을 요청한다. 있으면 `knowledge/` 를 갱신하고 검증한다.
2. 다시 끝날 때 `auto-commit.sh` 가 남은 변경을 커밋한다. push는 하지 않는다. merge·rebase 중이거나 detached HEAD면 건너뛰고, `.env`·`.claude/settings.local.json`·50MB 초과 파일은 커밋하지 않는다.

## 새 PC에서

1. Claude Code로 저장소를 열면 훅이 적용된다. 훅은 bash로 실행되므로 Windows에는 Git Bash가 필요하다.
2. OKF 검증기용 PyYAML을 한 번 설치한다(`.venv` 는 gitignore).

   ```bash
   python -m venv .claude/okf/.venv && .claude/okf/.venv/Scripts/python -m pip install pyyaml   # Windows
   python3 -m venv .claude/okf/.venv && .claude/okf/.venv/bin/python -m pip install pyyaml      # macOS/Linux
   ```

3. git 사용자가 설정되지 않았으면 자동 커밋은 `chungbrain` noreply 주소로 작성된다.

훅을 보거나 끄려면 Claude Code에서 `/hooks` 를 연다. 이 PC에서만 끄려면 `.claude/settings.local.json`(gitignore)에 `{ "disableAllHooks": true }` 를 둔다.

## 포함하지 않은 것

- `~/.claude/` 의 자격 증명, 대화 기록, 전역 설정, Claude 메모리 — 개인 데이터다.
- OpenCode용 지침: OpenCode는 `.claude/CLAUDE.md` 를 읽는다고 문서화되어 있지 않다. OpenCode에는 `skills/` 만 적용된다.
