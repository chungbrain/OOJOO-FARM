---
type: Environment
title: 개발 PC (Windows) 도구와 경로
description: 이 Windows 개발 PC에서 저장소·JDK·Android SDK·gcloud·git이 어디 있고 무엇이 빠져 있는지.
tags: [windows, android, tooling]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: session-2026-11-27
    resource: "Claude Code session on the Windows dev PC, 2026-11-27 (명령 실행 결과로 확인)"
    title: 2026-11-27 세션 관찰
  - id: build-skill
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/.claude/skills/build-apk-dist/SKILL.md
    title: build-apk-dist 스킬
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
---

저장소는 `C:\Users\samsung\dev\OOJOO-FARM` 에 있다.[^session-2026-11-27] `.claude/skills/*` (옛 `.opencode/skills`)에 적힌 `C:\OOJOO-FARM` 경로와 Android Studio `jbr` JAVA_HOME은 다른 PC 기준이라 이 PC에서는 맞지 않는다.[^build-skill]

| 도구 | 이 PC |
|---|---|
| JDK 17 | `C:/Users/samsung/tools/jdk-17.0.21.1+2` |
| Android SDK | `C:/Users/samsung/tools/android-sdk` (build-tools 34.0.0, platform-tools) |
| gcloud | `%LOCALAPPDATA%/Google/Cloud SDK/google-cloud-sdk/bin` — Git Bash PATH에 있음 |
| Python | 3.13, 전역 PyYAML 없음 |
| gh | Git Bash PATH에 없음 |
| git 사용자 | 설정 안 됨 → [커밋 관례](../playbooks/commit-and-diary.md) 참고 |

위 표는 2026-11-27 세션에서 확인했다.[^session-2026-11-27] 에뮬레이터가 없어서 앱 동작은 실기기에서만 확인할 수 있다.[^diary-2026-11-27]

# 함정

- `apksigner.bat` 은 멈춘다. `java -jar C:/Users/samsung/tools/android-sdk/build-tools/34.0.0/lib/apksigner.jar` 를 쓴다.[^session-2026-11-27]
- Gradle 빌드는 10분을 넘길 수 있다. 메모리가 부족하면 Claude Code가 백그라운드 Gradle 작업을 강제 종료하는데, 2026-11-27에는 이미 BUILD SUCCESSFUL 이후라 결과에 영향이 없었다.[^session-2026-11-27]
- 백엔드 `npm test` 의 SIGTERM graceful shutdown 테스트 1건은 Windows에서 항상 실패한다(`exit code null`, 환경 문제).[^diary-2026-11-27]

[^session-2026-11-27]: 2026-11-27 세션 관찰
[^build-skill]: build-apk-dist 스킬
[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
