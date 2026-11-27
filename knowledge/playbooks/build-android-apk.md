---
type: Playbook
title: "Android APK 빌드: 이 PC에서 debug APK를 dist에 올리기"
description: 이 PC의 JDK·SDK 경로로 debug APK를 빌드·검증해 dist/에 넣고 커밋하는 절차.
tags: [android, build, apk]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: session-2026-11-27
    resource: "Claude Code session on the Windows dev PC, 2026-11-27 (명령 실행 결과로 확인)"
    title: 2026-11-27 세션 관찰
  - id: build-skill
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/.opencode/skills/build-apk-dist/SKILL.md
    title: build-apk-dist 스킬
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
---

# Trigger

앱 코드를 바꾼 뒤 "빌드" 요청을 받았을 때. 모듈은 `:master:app` (사용자 앱)과 `:slave:app` (Farmer 앱)이다.[^build-skill] 경로는 [개발 PC](../infra/windows-dev-pc.md) 기준이다.

# Steps

1. `android/` 에서 테스트와 빌드를 함께 돌린다. 10분을 넘길 수 있으니 백그라운드로 실행한다.[^session-2026-11-27]

   ```bash
   JAVA_HOME=C:/Users/samsung/tools/jdk-17.0.21.1+2 ANDROID_HOME=C:/Users/samsung/tools/android-sdk \
     ./gradlew.bat :master:app:testDebugUnitTest :master:app:assembleDebug --console=plain --no-daemon
   ```

2. `android/build.gradle.kts` 의 dist 자동 복사가 동작하지 않을 때가 많다. 파일 시각을 보고 안 바뀌었으면 수동으로 복사한다.[^diary-2026-11-27]
   `android/master/app/build/outputs/apk/debug/app-debug.apk` → `dist/oojoo-farm-master-debug.apk` (Farmer는 `slave` → `dist/oojoo-farm-farmer-debug.apk`).[^build-skill]
3. 서명을 확인한다. SHA-256이 이전 APK와 같아야(이 PC 키 `d0a5726c…`) 기존 설치본 위에 업데이트 설치된다.[^diary-2026-11-27]

   ```bash
   java -jar C:/Users/samsung/tools/android-sdk/build-tools/34.0.0/lib/apksigner.jar verify --print-certs dist/oojoo-farm-master-debug.apk
   ```

4. 바뀐 앱의 APK만 `build: debug APKs <라벨> with …` 로 커밋한다. 라벨 `YYYYMMDDnn` 은 README 배지·What's New·커밋 제목에 쓰는 표시용이고, 앱의 `versionCode`/`versionName`(`android/*/app/build.gradle.kts`)과는 따로 관리된다. 2026-11-27 기준으로 라벨은 2026112701, master `versionCode` 는 2026112402였다.[^session-2026-11-27]

[^session-2026-11-27]: 2026-11-27 세션 관찰
[^build-skill]: build-apk-dist 스킬
[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
