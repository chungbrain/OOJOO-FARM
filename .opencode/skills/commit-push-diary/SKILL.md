---
name: commit-push-diary
description: OOJOO-FARM 저장소의 커밋·푸시 후 working_diary 작성까지의 표준 흐름. 커밋/푸시 요청 시 사용.
---

# 커밋 & 푸시 & 작업 일지

## 흐름

1. **상태 확인**
   ```
   git status; git diff --stat; git log --oneline -5
   ```
   - 스테이징할 파일만 선별, secrets 포함 여부 확인

2. **커밋**
   - 메시지 스타일: `feat:`, `fix:`, `docs:`, `build:`, `chore:` + 영어 요약 (저장소 관례)
   - 여러 파일을 논리 단위로 나눌 수 있으면 분할 커밋

3. **푸시**
   ```
   git push origin main
   ```

4. **working_diary 작성** (필수 — 생략 금지)
   - 경로: `working_diary/{date}_{commit_title}_{number}.md`
   - `date`: YYYY-MM-DD
   - `commit_title`: 커밋 제목에서 슬래시/공백 → 하이픈
   - `number`: 같은 날 같은 커밋 문서가 여러 개면 01, 02... 증가
   - 내용: 요구사항/문제 원인, 변경 파일 표, 동작 흐름, 검증 결과(빌드/배포 상태), 남은 작업
   - 커밋이 없는 인프라 작업도 주제명으로 기록

5. **diary까지 함께 커밋/푸시**
   - 커밋 메시지: `docs: add working_diary with YYYY-MM-DD session log`

## 참고

- 규칙 원문: `working_diary/README.md`
- 사용자가 "커밋해", "푸시해"라고 하면 diary 작성까지 포함함
