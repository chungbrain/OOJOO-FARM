// FCM (v1 HTTP API) 푸시 서비스 — 설정이 없으면 조용한 no-op.
//
// 설정 (env):
//   FCM_PROJECT_ID      — Firebase 프로젝트 ID (google-services.json 의 project_id)
//   FCM_SERVICE_ACCOUNT — 서비스 계정 키 JSON 파일 경로 (또는 인라인 JSON 문자열)
// 둘 중 하나라도 없으면 pushEnabled()=false, pushMasterEvent() 는 아무 동작도 하지 않는다.
// (google-services.json 이 없어도 빌드/실행이 정상인 것이 의도 — 설정만 넣으면 즉시 유효해진다.)
//
// FCM v1 플로우: 서비스 계정 키로 RS256 JWT 서명 → OAuth2 토큰 발급 →
// POST https://fcm.googleapis.com/v1/projects/{id}/messages:send
import crypto from 'node:crypto';
import fs from 'node:fs';
import db from '../db.js';
import { householdMemberIds } from './household.js';

const OAUTH_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

let fetchImpl = globalThis.fetch;
let nowMs = () => Date.now();
export const _test = {
  setFetch(fn) { fetchImpl = fn; },
  setNow(fn) { nowMs = fn; },
  flush: () => pushQueue, // 테스트: 인라인 푸시 큐 대기
};

function loadServiceAccount() {
  const proj = process.env.FCM_PROJECT_ID;
  const raw = process.env.FCM_SERVICE_ACCOUNT;
  if (!proj || !raw) return null;
  let sa;
  try {
    sa = raw.trim().startsWith('{') ? JSON.parse(raw) : JSON.parse(fs.readFileSync(raw, 'utf8'));
  } catch { return null; }
  if (!sa.client_email || !sa.private_key) return null;
  return { projectId: proj, clientEmail: sa.client_email, privateKey: sa.private_key };
}

export function pushEnabled() {
  return loadServiceAccount() != null;
}

// ---- RS256 JWT (node:crypto 만으로, 외부 의존성 없음) ----
function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function signJwt(sa) {
  const t = Math.floor(nowMs() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({
    iss: sa.clientEmail,
    scope: FCM_SCOPE,
    aud: OAUTH_TOKEN_URL,
    iat: t,
    exp: t + 3600,
  }));
  const sig = b64url(crypto.createSign('RSA-SHA256').update(`${header}.${claims}`).sign(sa.privateKey));
  return `${header}.${claims}.${sig}`;
}

// ---- OAuth2 액세스 토큰 (캐시 — 만료 5분 전 갱신) ----
let tokenCache = { value: null, expMs: 0 };
async function accessToken(sa) {
  if (tokenCache.value && nowMs() < tokenCache.expMs) return tokenCache.value;
  const body = new URLSearchParams({
    grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
    assertion: signJwt(sa),
  });
  const resp = await fetchImpl(OAUTH_TOKEN_URL, { method: 'POST', body });
  if (!resp.ok) throw new Error(`fcm token http ${resp.status}`);
  const j = await resp.json();
  if (!j.access_token) throw new Error('fcm token: no access_token');
  const expMs = nowMs() + (Number(j.expires_in) || 3600) * 1000 - 5 * 60 * 1000;
  tokenCache = { value: j.access_token, expMs };
  return j.access_token;
}

// ---- 메시지 전송 ----
/**
 * FCM token 1개로 data 메시지 전송. data 만 실어 보낸다 — 마스터 앱 FcmService 가
 * data.type 으로 라벨/색을 정해 로컬 알림을 렌더링한다 (FCM notification 필드 미사용).
 */
export async function sendPush(fcmToken, data) {
  const sa = loadServiceAccount();
  if (!sa) return { skipped: true };
  const at = await accessToken(sa);
  const resp = await fetchImpl(
    `https://fcm.googleapis.com/v1/projects/${sa.projectId}/messages:send`,
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${at}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: { tokens: [fcmToken], data } }),
    }
  );
  if (!resp.ok) throw new Error(`fcm send http ${resp.status}`);
  return { sent: true };
}

// ---- 마스터 이벤트 푸시 (events 루트의 SSE chokepoint 에서 호출) ----
function userPushTokens(userId) {
  const row = db.prepare('SELECT push_tokens FROM users WHERE id=?').get(userId);
  try {
    const arr = row?.push_tokens ? JSON.parse(row.push_tokens) : [];
    return Array.isArray(arr) ? arr.filter((t) => typeof t === 'string' && t.length > 0) : [];
  } catch { return []; }
}

async function doPush(slaveId, event) {
  try {
    if (!pushEnabled()) return { skipped: 'disabled' };
    const slave = db.prepare('SELECT user_id FROM slaves WHERE id=?').get(slaveId);
    if (!slave?.user_id) return { skipped: 'no-owner' };
    const memberIds = householdMemberIds(slave.user_id);
    let sent = 0;
    for (const uid of memberIds) {
      for (const token of userPushTokens(uid)) {
        try {
          await sendPush(token, {
            type: event.type,
            eventId: event.eventId || null,
            slaveId,
            plantId: event.plantId || null,
            payload: JSON.stringify(event.payload || {}),
          });
          sent++;
        } catch (e) {
          console.warn(`[push] fcm send failed (token …${token.slice(-6)}): ${e.message}`);
        }
      }
    }
    return { sent };
  } catch (e) {
    console.warn(`[push] pushMasterEvent failed: ${e.message}`);
    return { skipped: 'error' };
  }
}

// 순서 큐: 이벤트 요청은 즉시 응답하고, 푸시는 백그라운드에서 처리.
// 절대 reject 되지 않음 (doPush 는 내부 try/catch 완결).
let pushQueue = Promise.resolve();
export function pushMasterEvent(slaveId, event) {
  pushQueue = pushQueue.then(() => doPush(slaveId, event));
  return pushQueue;
}
