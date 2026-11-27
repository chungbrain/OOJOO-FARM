# 2026-11-27 에이전트 개발 환경을 .claude 로 통합 (claude-agent-environment)

## 요구사항

OOJOO-FARM 개발에 쓰인 에이전트 환경(agents.md, 스킬, 훅 등)을 모두 저장소의 `.claude/` 아래에 정리하고 push.

## 조사 결과 (무엇이 있었나)

| 항목 | 위치 | 비고 |
|---|---|---|
| OpenCode 스킬 5개 | `.opencode/skills/*/SKILL.md` (2026-08-23 추가, 커밋됨) | build-apk-dist, commit-push-diary, deploy-backend-gcp, oojoo-project-map, sync-from-github |
| Claude Code 훅 + OKF 도구 | `.claude/settings.local.json`, `.claude/hooks/`, `.claude/okf/` | 같은 날 만든 것, 이 PC 전용(gitignore)이었음 |
| OKF 지식 번들 | `knowledge/` | 같은 날 생성 |
| AGENTS.md / CLAUDE.md | 없음 | 저장소·git 이력·사용자 홈(~/.config/opencode, ~/.claude) 모두 확인 |
| `~/.claude/` | 자격 증명·대화 기록·전역 설정, Claude 메모리 | 개인 데이터라 제외 |

## 정리 결과

```
.claude/
  CLAUDE.md          프로젝트 작업 지침 (새로 작성 — AGENTS.md 역할, Claude Code가 세션마다 로드)
  README.md          폴더 안내, 새 PC 설정 방법
  settings.json      SessionStart·Stop 훅 (settings.local.json 에서 옮김 → 모든 PC에 적용)
  hooks/             turn-end.sh, auto-commit.sh, okf-session-start.sh
  skills/            .opencode/skills 5개를 git mv (이력 유지)
  knowledge/         knowledge/ OKF 번들을 git mv
  okf/               GUIDE.md, validate.sh, validate.py(+LICENSE-okf.txt, Apache-2.0)
```

- 경로 수정: 훅·GUIDE·validate.sh 의 `knowledge/` → `.claude/knowledge/`, 지식 개념의 스킬 출처 URL `.opencode/skills` → `.claude/skills`
- `validate.sh`: venv가 없으면 PyYAML 있는 시스템 Python을 찾고, 없으면 설치 명령을 안내(exit 2)
- `.gitignore`: `.claude/settings.local.json`, `.claude/okf/.venv/` 만 로컬, `.opencode/` 는 통째로 무시(스킬은 옮겨 옴)
- `knowledge/infra/claude-code-hooks.md` 를 "저장소 공유 설정" 기준으로 갱신, 함정 2건(빈 응답, reason 큰따옴표) 기록

## OpenCode 호환

- OpenCode 문서(opencode.ai/docs/skills): 프로젝트 호환 경로로 `.claude/skills` 를 읽음 → 스킬은 OpenCode에서도 그대로 동작
- 지침: OpenCode는 AGENTS.md가 없으면 `CLAUDE.md` 를 읽지만 `.claude/CLAUDE.md` 경로는 문서에 없음 → OpenCode에는 스킬만 적용

## 검증 결과

- `bash .claude/okf/validate.sh` → 12 files, 0 errors, 0 warnings
- 훅 입력을 흉내 낸 실행: Stop 1회차 block(새 경로 안내), SessionStart 목록 4개 index 출력, `settings.json` JSON 정상
- venv 없는 상태 흉내: 설치 안내 후 exit 2

## 주의

- 훅이 저장소 설정이 되어 이 저장소를 Claude Code로 여는 모든 사람·PC에서 턴마다 자동 커밋된다. 한 PC에서만 끄려면 `.claude/settings.local.json` 에 `{ "disableAllHooks": true }`
- 훅은 bash 실행 — Windows는 Git Bash 필요. 새 클론에서는 OKF 검증기 venv를 한 번 만들어야 함(`.claude/README.md`)

## 남은 작업

- 다음 세션 시작 때 `.claude/settings.json` 훅과 `.claude/CLAUDE.md` 가 실제로 로드되는지 확인
