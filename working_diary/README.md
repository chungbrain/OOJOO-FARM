# Working Diary 규칙

이 저장소의 모든 작업은 `working_diary/` 폴더에 기록한다.

## 파일명 형식

```
{date}_{commit_title}_{number}.md
```

- `date`: `YYYY-MM-DD`
- `commit_title`: 커밋 제목 (슬래시/공백은 하이픈으로 치환)
- `number`: 같은 날 같은 커밋에 대한 문서가 여러 개일 때 2자리 순번 (`01`, `02`, ...)

예: `2026-08-22_background-camera-instant-capture_01.md`

## 기록 내용

- 작업을 수행했거나 완료된 내용
- 문제 원인과 해결 방법
- 변경 파일 목록
- 검증 결과 (빌드/테스트/배포 상태)
- 남은 작업

## 타이밍

- 커밋/푸시 시점에 작성
- 인프라 등 커밋이 없는 작업도 일지로 남긴다 (commit_title 자리에 작업 주제 사용)
