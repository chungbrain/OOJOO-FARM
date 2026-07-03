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

## API (Phase 1)

### 페어링
| 메서드 | 경로 | 본문 | 응답 |
|--------|------|------|------|
| POST | /api/pairing/code | { userId } | { code, expiresAt } |
| POST | /api/pairing/verify | { code } | { slaveId, sessionKey, userId } |
| GET | /api/pairing/:userId | | { slaves[] } |
| POST | /api/pairing/heartbeat | { slaveId } | { ok } |

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
| POST | /api/commands | { slaveId, plantId, action, amountMl, weatherFactor } | { commandId, status } |
| GET | /api/commands/pending/:slaveId | | { commands[] } |
| POST | /api/commands/:id/done | | { ok } |
| GET | /api/commands/history/:slaveId | | { commands[] } |

### 날씨 (Open-Meteo 연동)
| 메서드 | 경로 | 응답 |
|--------|------|------|
| GET | /api/weather/:region | { region, temp, humidity, precipitation, weatherCode, weatherFactor } |

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
- 테이블: users, slaves, pairings, plants, events, waterings, commands, weather_cache
