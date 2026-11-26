# OOJOO 로컬 model-manager

이 PC 에서 Farmer 온디바이스 모델(검출 모듈 · 식물 종 모델)의 학습 사진과 라벨을 관리하고, 학습·평가하고,
결과를 **서버(배포)** 와 **GitHub(저장소)** 에 올리는 도구. 자세한 설명: [docs/model-manager.md](../../docs/model-manager.md)

```
scripts\model-manager.bat          # Windows — 처음 한 번 npm install, 브라우저 열고 실행
# 또는
cd ml/model-manager && npm install && npm start      # http://127.0.0.1:4900
npm test                                              # 학습 엔진 + 서버 업로드 + GitHub E2E
```

- 처음 실행하면 `ml/registry/` 에 앱 내장 모델 16개가 v1 로 생긴다.
- 화면 오른쪽 위 **설정**: 서버 주소(기본 `http://34.50.40.104:4000`), 업로드 토큰(서버 `MODEL_MANAGER_TOKEN`), GitHub 커밋 이름/이메일.
  설정은 `ml/model-manager/.local/config.json` — GitHub 에 올라가지 않는다.
- 127.0.0.1 에만 열린다. 다른 사이트에서 오는 요청은 막는다.

| 폴더 | 내용 |
|---|---|
| `server.mjs` | 로컬 API + 화면 |
| `ui/index.html` | 화면 (목록 · 성능 · 학습 데이터 · 학습·업로드·배포 · GitHub) |
| `lib/store.js` | 파일 레지스트리 (`ml/registry/`) |
| `lib/train.js`, `lib/worker.js`, `lib/jobs.js` | 학습·평가 (worker thread) — 모델 연산은 `backend/src/ml/ojp1.js` 공유 |
| `lib/image.js` | JPEG 디코드(EXIF 회전)·RoI 크롭·축소 |
| `lib/remote.js` | 서버 연결 — 현장 성능·farmer 사진 조회, 버전 업로드·배포 |
| `lib/git.js` | `ml/registry/<모델>` 만 커밋해서 push |
