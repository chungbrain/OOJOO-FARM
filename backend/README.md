# OOJOO FARM Backend (Phase 1 MVP)

Node.js + Express + SQLite 백엔드. 마스터/슬레이브 페어링, 식물, 이벤트, 관수 API 제공.

## 요구사항
- Node.js 22 이상 — https://nodejs.org (`node:sqlite` 내장 모듈 사용, 22.5+ 필요)

## Node.js 설치 (Windows)
- 권장: https://nodejs.org 에서 LTS 인스톨러 다운로드 후 설치
- 또는 관리자 PowerShell: `winget install OpenJS.NodeJS.LTS`
- 확인: `node -v` / `npm -v`

## 실행
```bash
cd backend
copy .env.example .env
npm install
npm start
# OOJOO FARM backend on :4000
```
개발(자동 재시작): `npm run dev`

## 선택 환경변수 (Phase 6 — 미설정 시 해당 기능 off)

| 변수 | 효과 |
|---|---|
| `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT` | FCM push 활성화 (서비스 계정 키 JSON 경로 또는 인라인 JSON). 마스터 앱 `FirebaseCfg.kt` 도 함께 채워야 디바이스까지 도달. 대상: `health_change`/`water_low`/`pest_detected`/`harvest_ready` + `water_failed`/`battery_low`, 수신: 소유자+가구원 전체의 `POST /api/users/:id/push-token` 등록 디바이스. |
| `PG_MODE=toss` + `TOSS_SECRET_KEY` | 마켓 체크아웃 → `pending_payment` (재고 미차감) → 서버가 Toss 결제 생성/확인. 미설정 시 기존 데모(즉시 `paid`). |
| `TOSS_WEBHOOK_SECRET` | Toss 웹훅(`POST /api/payments/toss/webhook`) HMAC 검증 키 — 포털에 웹훅 등록 시 사용. |

## 인증 (세션키)

슬레이브 전용 엔드포인트는 페어링 `verify` 로 발급받은 `sessionKey` 를
**`x-session-key` 헤더**로 보내야 한다. 없거나 틀리면 401/403.

세션키가 필요한 엔드포인트: `POST /api/pairing/heartbeat`,
`POST /api/watering/log`, `GET /api/commands/pending/:slaveId`
(`POST /api/events` 는 헤더가 있으면 검증, 없으면 통과 — 하위호환).

## API (Phase 1)

### 계정
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| POST | /api/users | { id?, nickname?, region? } | user (upsert) |
| GET | /api/users/:id | | user |

### 페어링
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| POST | /api/pairing/code | { userId } | { code, expiresAt } |
| POST | /api/pairing/verify | { code } | { slaveId, sessionKey, userId } |
| GET | /api/pairing/:userId | | { slaves[] } (online/last_seen/battery 포함) |
| POST | /api/pairing/heartbeat | { slaveId, battery? } | { ok } (세션키 필요) |
| DELETE | /api/pairing/:slaveId | | { ok } (연결 해제) |

### 식물
| POST | /api/plants | { userId, slaveId, name, species, plantedAt, stage } | { plantId } |
| GET | /api/plants/:userId | | { plants[] } |
| GET | /api/plants/plant/:id | | plant |

### 이벤트 (슬레이브 보고)
| POST | /api/events | { slaveId, plantId, type, payload } | { eventId } |
| GET | /api/events/:slaveId | | { events[] } |

### 관수
| POST | /api/watering/log | { slaveId, plantId, amountMl, source, weatherFactor } | { logId } |
| GET | /api/watering/:plantId | | { waterings[] } |

### 원격 지시 큐 (마스터→슬레이브)
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| POST | /api/commands | { slaveId, plantId, action(water\|pause\|resume\|fan\|laser\|capture_photo\|capture_video\|generate_growth_clip\|diagnose_video), amountMl, weatherFactor } | { commandId, status } |
| GET | /api/commands/pending/:slaveId | | { commands[] } |
| POST | /api/commands/:id/done | | { ok } |
| POST | /api/commands/:id/cancel | | { ok } (queued 상태만) |
| GET | /api/commands/history/:slaveId | | { commands[] } |

### 자율 실행 정책 (마스터 설정 ↔ 슬레이브 동기화)
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| GET | /api/policy/:slaveId | | { slaveId, water_auto, fan_auto, laser_approval, capture_interval, pest_interval, roi_interval, region, camera_mount } |
| PUT | /api/policy/:slaveId | { waterAuto?, fanAuto?, laserApproval?, captureInterval?(10~360), pestInterval?(5~120), roiInterval?, region?, cameraMount?(auto\|landscape\|portrait) } | 갱신된 정책 |

### 알림 센터 (마스터)
| 메서드 | 경로 | 응답 |
|--------|------|------|
| GET | /api/notifications/:userId | { notifications[] } (사용자 모든 Farmer 이벤트 집계, 슬레이브/식물명 조인) |

### 마켓플레이스
| 메서드 | 경로 | 본문/쿼리 | 응답 |
|--------|------|-----------|------|
| GET | /api/market/categories | | { categories[] } (상품수 포함) |
| GET | /api/market/products | ?category=&q=&sort= | { products[] } |
| GET | /api/market/products/:id | | product |
| GET | /api/market/bundles | | { bundles[] } (구성 상품 포함) |
| GET | /api/market/recommendations/:userId | | { recommendations[] } (식물 기반) |
| POST | /api/market/affiliate/:id | { userId? } | { url } (클릭 트래킹) |
| POST | /api/market/orders | { userId, items:[{productId,qty}] } | { orderId, total, status } |
| GET | /api/market/orders/:userId | | { orders[] } (항목 포함) |

### 리포트 / 구독
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| GET | /api/report/:slaveId | | { watering{count,totalMl,autoCount,manualCount}, harvestReady, pestDetected, anomalies, lastWatering } (7일) |
| GET | /api/subscription/plans | | { plans[] } |
| GET | /api/subscription/:userId | | { plan, maxFarmers, detailedReport, priorityCs, price } |
| POST | /api/subscription | { userId, plan(free\|premium) } | 갱신된 구독 |

### 커뮤니티 (지역 나눔/판매/구입)
| 메서드 | 경로 | 본문/쿼리 | 응답 |
|--------|------|-----------|------|
| GET | /api/community/posts | ?region=&type=&q=&viewerId= | { posts[] } (작성자/평판·첨부 `media[]` 포함, 차단 제외) |
| GET | /api/community/posts/:id | | { post, comments[] } (`post.media[]` = 첨부 사진·동영상, 표시 순서) |
| POST | /api/community/posts | { userId, type(share\|sell\|buy), title, crop?, quantity?, price?, region?, description?, image?, mediaIds?[] } | { postId } — mediaIds 는 이 사용자가 올린, 아직 안 붙은 미디어(최대 10) |
| POST | /api/community/media | multipart: `file`(사진 jpeg/png/webp ≤20MB · 동영상 mp4/3gp/webm/mov/mkv ≤100MB), `thumb`?(이미지 ≤2MB), userId, width?, height?, durationMs? | { id, kind(photo\|video), url, thumb_url, mime, size, width, height, duration_ms } — 413 크기 초과, 415 형식 |
| GET | /api/community/media/file/:name | Range 지원 | 파일 (등록된 원본·썸네일만) |
| DELETE | /api/community/media/:id | { userId } | { ok } — 글에 붙기 전 내 업로드만 (붙은 것 409). 안 붙은 업로드는 24시간 뒤 자동 정리 |
| POST | /api/community/posts/:id/comments | { userId, body } | { commentId } |
| PATCH | /api/community/posts/:id/status | { status(open\|reserved\|done) } | { ok } (done 시 작성자 평판 +1) |
| POST | /api/community/report | { reporterId, postId?, targetUserId?, reason? } | { ok } |
| POST / DELETE | /api/community/block | { blockerId, blockedId } | { ok } |
| GET | /api/community/reputation/:userId | | { score, deals } |
| GET | /api/community/mine/:userId | | { posts[] } |

### 날씨 (Open-Meteo 연동)
| 메서드 | 경로 | 응답 |
|--------|------|------|
| GET | /api/weather/:region | { region, temp, humidity, precipitation, weatherCode, weatherFactor } |

### 관리 / 모니터링
| 메서드 | 경로 | 응답 |
|--------|------|------|
| GET | /api/admin/stats | { devices, apiUsage, crashes, timeSeries } |
| GET | /api/admin/dashboard | 전체 집계 — overview(개요), master(마스터 앱 활동), farmer(팜어 활동), relations(마스터↔팜어 관계: 페어링/가구), market(주문/매출/상품/제휴), community(게시글/댓글/평판), system(API 트래픽/5xx/크래시) |
| GET | /admin | self-contained 모니터링 페이지 (CDN 없음, 15초 자동 새로고침) — 위 dashboard JSON 렌더링 |

기존 admin API와 동일한 신뢰 모델 (PRD상 마스터 API 비인증). 운영망에 열지 말 것.

## 테스트
```bash
npm test    # node:test 통합 테스트 (임시 DB 로 격리)
```

### 식물 (슬레이브 할당)
| 메서드 | 경로 | 응답 |
|--------|------|------|
| GET | /api/plants/slave/:slaveId | { plants[] } |

## 빠른 테스트 (curl)
```bash
curl http://localhost:4000/health
curl -X POST http://localhost:4000/api/pairing/code -H "Content-Type: application/json" -d "{\"userId\":\"u1\"}"
# 응답 code를 복사
curl -X POST http://localhost:4000/api/pairing/verify -H "Content-Type: application/json" -d "{\"code\":\"ABC123\"}"
```

## 데이터
- SQLite 파일: `data/oojoo.db` (자동 생성)
- 테이블: users, slaves, pairings, plants, events, waterings, commands, policies, weather_cache, products, bundles, orders, order_items, affiliate_clicks, community_posts, community_comments, community_reputation, community_reports, community_blocks, community_media
- data 디렉터리는 기동 시 자동 생성됨 (이웃 글 첨부 파일: `data/community/`, `COMMUNITY_MEDIA_DIR` 로 변경 가능)
- 마켓 상품/번들은 최초 기동 시 자동 시드됨 (`src/lib/marketSeed.js`)
