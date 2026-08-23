---
name: sync-from-github
description: 원격 GitHub 저장소의 최신 내용을 로컬 OOJOO-FARM로 강제 동기화하는 절차. 동기화 요청 시 사용.
---

# GitHub → 로컬 동기화

## 절차

1. **상태 확인**
   ```powershell
   git status; git remote -v
   git fetch origin
   git diff --name-only HEAD origin/main
   ```

2. **로컬 변경 처리 (충돌 시 사용자에게 질문)**
   - 로컬 수정이 있으면 겹치는지 확인 후 선택지 제시:
     - 버리고 동기화: `git reset --hard origin/main` (권장)
     - 백업 후 동기화: `git stash` 후 reset
     - 커밋 후 병합

3. **동기화 실행**
   ```powershell
   git reset --hard origin/main
   ```
   - fast-forward 가능하면 `git pull`로 충분

4. **확인**
   ```powershell
   git status   # "up to date with 'origin/main'"
   ```

## 참고

- 저장소: `https://github.com/chungbrain/OOJOO-FARM.git`
- 로컬 경로: `C:\OOJOO-FARM`
- 동기화 후 로컬에서 띄운 백엔드 서비스가 있으면 재시작 고려
