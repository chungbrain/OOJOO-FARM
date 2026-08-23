---
name: deploy-backend-gcp
description: OOJOO-FARM 백엔드를 GCP VM(34.50.40.104)에 배포하는 절차. 백엔드 배포/업데이트 요청 시 사용.
---

# GCP 백엔드 배포

## 인프라 정보

- GCP 프로젝트: `oojoo-farm`
- VM: `oojoo-backend` (e2-small, zone `asia-northeast3-a`, 서울)
- 고정 IP: `34.50.40.104`, 포트 4000 오픈 (방화벽 `allow-oojoo-4000`)
- 소스: VM 내 `/opt/oojoo/repo` (GitHub clone)
- 서비스: systemd `oojoo.service` (자동 재시작)
- 접속 주소: `http://34.50.40.104:4000/`

## gcloud 실행 (이 Windows PC)

gcloud가 PATH에 없음 — 전체 경로 사용:
```powershell
$gcloud = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
```

## 배포 절차

1. **로컬 커밋을 먼저 푸시** (VM은 GitHub에서 pull 받음)

2. **VM에서 pull + 재시작**
   ```powershell
   & $gcloud compute ssh oojoo-backend --zone=asia-northeast3-a --command="cd /opt/oojoo/repo && git pull 2>&1 | tail -3 && cd backend && npm install --no-audit --no-fund 2>&1 | tail -1 && sudo systemctl restart oojoo && sleep 2 && sudo systemctl is-active oojoo" --quiet
   ```
   - 응답 `active` 확인

3. **헬스체크 (로컬 PC에서)**
   ```powershell
   Invoke-WebRequest -Uri "http://34.50.40.104:4000/health" -UseBasicParsing -TimeoutSec 10
   ```
   - `200 {"ok":true,...}` 확인

## 주의

- SSH heredoc은 Windows cmd를 거치며 깨짐 — `printf` 한 줄 방식 사용
- 첫 SSH 접속 시 호스트 키 캐시 프롬프트 → `echo y |` 로 응답
- 소스 pull은 배포 단계이므로 반드시 로컬 push 선행
