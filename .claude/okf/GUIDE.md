# .claude/knowledge/ 번들 관리 규칙 (OKF v0.3)

턴이 끝날 때 Stop 훅이 이 규칙대로 `.claude/knowledge/`를 점검하라고 요청한다.
스펙 원문: https://github.com/open-knowledge-format/open-knowledge-format/blob/main/SPEC.md

## 1. 무엇을 기록하나 — 선별이 핵심

다음 세션의 내가 알면 시간을 아끼거나 실수를 피하는 것만 기록한다.

- 기록: 환경·경로·도구의 특이점(이 PC에서만 맞는 것 포함), 실제로 통한 절차, 인프라 사실(서버·서비스·저장 위치),
  설계 결정과 그 이유, 기능 계약(API·한도·데이터 흐름), 함정과 해결법, 바뀐 사실(옛 지식 정정).
- 기록하지 않음: 코드·README·working_diary를 보면 바로 알 수 있는 내용(링크만 건다), 이번 턴에만 의미 있는 진행 상황,
  추측, 사소한 대화, 비밀(토큰·비밀번호·키 값), 사용자 이메일 등 개인정보.
- 나에 대한 작업 방식 피드백(말투, 선호)은 OKF가 아니라 Claude 메모리에 둔다.
- 기록할 게 없으면 파일을 건드리지 않고 "기록할 새 지식 없음" 한 줄만 답한다(빈 응답은 Claude Code가 다시 요구한다). 매 턴 기록하는 게 목표가 아니다.

## 2. 어디에, 어떻게

- 새 파일보다 기존 개념 갱신을 우선한다. 한 파일 = 한 개념, 본문은 짧게(대략 80줄 이내).
- 디렉터리: `infra/`(서버·PC·도구), `playbooks/`(절차), `features/`(기능 계약·설계 결정). 꼭 필요할 때만 새 디렉터리.
- 파일 이름은 소문자-하이픈. `index.md`, `log.md`는 개념 이름으로 쓰지 않는다.
- 본문은 한국어, 코드·명령·경로는 원문 그대로.

## 3. 프런트매터

```yaml
---
type: Playbook                     # 필수. 기존 type 재사용: Server, Environment, Tooling, Playbook, Feature
title: 사람이 읽는 제목
description: 한 문장 요약.          # index.md 항목에 그대로 복사
tags: [backend, gcp]               # 반드시 YAML 리스트
generated: { by: "claude-code/<모델 ID>", at: "<지금 UTC, 예: 2026-11-27T04:48:43Z>" }
sources:
  - id: diary-2026-11-27           # 본문 각주 [^diary-2026-11-27]의 키
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/<경로>
    title: 짧은 제목
---
```

- `generated.at`은 의미가 바뀐 변경에서만 갱신한다(서식·링크 수정은 유지).
- `sources`: 저장소 파일은 위 GitHub URL, 세션에서 직접 확인한 사실은 범위 서술자
  (예: `resource: "Claude Code session on the Windows dev PC, 2026-11-27"`). 개별 주장은 `[^id]` 각주로 근거를 단다.
- `verified`는 쓰지 않는다. 사용자가 내용을 확인했다고 말하고 자기 id를 알려 준 경우에만 `{ by: "human:<id>", at: ... }`.
  git 설정이나 PC 계정명에서 id를 추측하지 않는다.
- 불확실하거나 미완성이면 `status: draft`. 낡은 개념은 지우지 말고 `status: deprecated` + `superseded_by: /<새 경로>.md`, 본문에 이유.
- `stale_after`는 갱신 주기를 실제로 알 때만. 날짜를 지어내지 않는다.
- 본문 링크는 파일 기준 상대 경로(`../playbooks/x.md`), 경로 필드(`superseded_by` 등)는 `/`로 시작.
- 위키링크 `[[...]]`는 OKF 링크가 아니다. 일반 마크다운 링크만 쓴다.

## 4. 바꿨다면 함께 할 일

1. 바꾼 디렉터리의 `index.md` 항목(`* [제목](파일.md) - description`)과 루트 `index.md` 갱신. 하위 index.md에는 프런트매터 없음.
2. `log.md` 맨 위 날짜(`## YYYY-MM-DD`, 최신이 위) 아래에 `* **Update**: ...` / `* **Creation**: ...` / `* **Deprecation**: ...` 한 줄.
3. 검증: `bash .claude/okf/validate.sh` → 오류 0, 경고는 고치거나 이유를 남긴다.
4. 사용자에게는 마지막에 한 줄로 바꾼 개념만 알린다. 커밋은 Stop 훅이 한다.
