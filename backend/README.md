# OOJOO FARM Backend (Phase 1 MVP)

Node.js + Express + SQLite 백엔드. 마스터/슬레이브 페어링, 식물, 이벤트, 관수 API 제공.

## 요구사항
- Node.js 18 LTS 이상 — https://nodejs.org

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
| POST | /api/watering/command | { slaveId, plantId, amountMl, weatherFactor } | { commandId, status } |
| POST | /api/watering/log | { slaveId, plantId, amountMl, source, weatherFactor } | { logId } |
| GET | /api/watering/:plantId | | { waterings[] } |

### 원격 지시 큐 (마스터→슬레이브)
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| POST | /api/commands | { slaveId, plantId, action(water\|pause\|resume\|fan\|laser), amountMl, weatherFactor } | { commandId, status } |
| GET | /api/commands/pending/:slaveId | | { commands[] } |
| POST | /api/commands/:id/done | | { ok } |
| GET | /api/commands/history/:slaveId | | { commands[] } |

### 자율 실행 정책 (마스터 설정 ↔ 슬레이브 동기화)
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| GET | /api/policy/:slaveId | | { waterAuto, fanAuto, laserApproval, captureInterval, region } |
| PUT | /api/policy/:slaveId | { waterAuto?, fanAuto?, laserApproval?, captureInterval?, region? } | 갱신된 정책 |

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
| GET | /api/community/posts | ?region=&type=&q=&viewerId= | { posts[] } (작성자/평판 포함, 차단 제외) |
| GET | /api/community/posts/:id | | { post, comments[] } |
| POST | /api/community/posts | { userId, type(share\|sell\|buy), title, crop?, quantity?, price?, region?, description?, image? } | { postId } |
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
- 테이블: users, slaves, pairings, plants, events, waterings, commands, policies, weather_cache, products, bundles, orders, order_items, affiliate_clicks, community_posts, community_comments, community_reputation, community_reports, community_blocks
- data 디렉터리는 기동 시 자동 생성됨
- 마켓 상품/번들은 최초 기동 시 자동 시드됨 (`src/lib/marketSeed.js`)
