// 백엔드 REST + SSE 클라이언트 — android/slave ApiService.kt 를 미러.
// slave 인증은 페어링 verify 로 발급받은 sessionKey 를 'x-session-key' 헤더로 전송한다.
import http from 'node:http';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function jsonFetch(url, { method = 'GET', headers = {}, body } = {}) {
  const res = await fetch(url, {
    method,
    headers: { ...headers, ...(body !== undefined ? JSON_HEADERS : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!res.ok) {
    const err = new Error(`${method} ${url} → ${res.status}: ${text.slice(0, 200)}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

export class Api {
  /** @param {string} baseUrl 예) http://127.0.0.1:4000 */
  constructor(baseUrl, { slaveId = null, sessionKey = null } = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.slaveId = slaveId;
    this.sessionKey = sessionKey;
  }

  authHeaders() {
    return this.sessionKey ? { 'x-session-key': this.sessionKey } : {};
  }

  // === Slave 측 (FarmerEngine 가 호출하는 것과 동일한 엔드포인트) ===

  async heartbeat(battery = 100) {
    return jsonFetch(`${this.baseUrl}/api/pairing/heartbeat`, {
      method: 'POST', headers: this.authHeaders(), body: { slaveId: this.slaveId, battery },
    });
  }

  async policy() {
    const data = await jsonFetch(`${this.baseUrl}/api/policy/${this.slaveId}`, { headers: this.authHeaders() });
    return data;
  }

  async reportEvent(type, payload = {}, plantId = null) {
    return jsonFetch(`${this.baseUrl}/api/events`, {
      method: 'POST', headers: this.authHeaders(),
      body: { slaveId: this.slaveId, plantId, type, payload },
    });
  }

  async reportAnalysis(plantId, analysis) {
    return jsonFetch(`${this.baseUrl}/api/analysis/report`, {
      method: 'POST', headers: this.authHeaders(),
      body: { slaveId: this.slaveId, plantId, analysis },
    });
  }

  async pendingCommands() {
    return jsonFetch(`${this.baseUrl}/api/commands/pending/${this.slaveId}`, { headers: this.authHeaders() });
  }

  async commandDone(commandId) {
    return jsonFetch(`${this.baseUrl}/api/commands/${commandId}/done`, { method: 'POST', headers: this.authHeaders() });
  }

  async wateringLog({ plantId = null, amountMl = 300, source = 'manual', weatherFactor = 1 } = {}) {
    return jsonFetch(`${this.baseUrl}/api/watering/log`, {
      method: 'POST', headers: this.authHeaders(),
      body: { slaveId: this.slaveId, plantId, amountMl, source, weatherFactor },
    });
  }

  async slavePlants() {
    return jsonFetch(`${this.baseUrl}/api/plants/slave/${this.slaveId}`, { headers: this.authHeaders() });
  }

  /** PNG 사진 업로드 — multipart. 업로드 성공 시 photo_ready SSE 가 master 에 push 된다. */
  async uploadPhoto(png, { plantId, takenAt, commandId = null, location = 'sim' } = {}) {
    const fd = new FormData();
    fd.append('photo', new Blob([png], { type: 'image/png' }), `still_${Date.now()}.png`);
    fd.append('plantId', plantId);
    fd.append('takenAt', takenAt);
    fd.append('location', location);
    if (commandId) fd.append('commandId', commandId);
    const res = await fetch(`${this.baseUrl}/api/photos/upload/${this.slaveId}`, {
      method: 'POST', headers: this.authHeaders(), body: fd,
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(`uploadPhoto → ${res.status}: ${JSON.stringify(data).slice(0, 200)}`);
    return data;
  }

  // === 정적 헬퍼 (페어링 전 / master 측) ===

  static async health(baseUrl) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/health`);
  }

  static async pairCode(baseUrl, userId) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/pairing/code`, { method: 'POST', body: { userId } });
  }

  static async verify(baseUrl, code, name = 'Sim-Farmer') {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/pairing/verify`, { method: 'POST', body: { code, name } });
  }

  static async createUser(baseUrl, { nickname, region } = {}) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/users`, { method: 'POST', body: { nickname, region } });
  }

  static async createPlant(baseUrl, { userId, slaveId, name, species, stage = 'growing' }) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/plants`, {
      method: 'POST', body: { userId, slaveId, name, species, stage },
    });
  }

  static async setPolicy(baseUrl, slaveId, patch) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/policy/${slaveId}`, { method: 'PUT', body: patch });
  }

  static async sendCommand(baseUrl, { slaveId, plantId = null, action = 'capture_photo', amountMl = 300 }) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/commands`, {
      method: 'POST', body: { slaveId, plantId, action, amountMl },
    });
  }

  static async notifications(baseUrl, userId) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/notifications/${userId}`);
  }

  static async latestAnalysis(baseUrl, plantId) {
    return jsonFetch(`${baseUrl.replace(/\/$/, '')}/api/analysis/latest/${plantId}`);
  }

  static async download(baseUrl, urlPath) {
    const res = await fetch(`${baseUrl.replace(/\/$/, '')}${urlPath}`);
    if (!res.ok) throw new Error(`download ${urlPath} → ${res.status}`);
    const buf = Buffer.from(await res.arrayBuffer());
    return { buf, contentType: res.headers.get('content-type') };
  }
}

/**
 * SSE 구독 — node:http 스트림 파싱 (fetch/AbortController 소켓 종료 지연 이슈 회피).
 * data: {json} 이벤트만 onEvent 로 전달하고 ': heartbeat' 주석은 무시한다.
 * @returns {{ close: () => void, events: any[] }}
 */
export function openSse(baseUrl, path, { headers = {}, onEvent } = {}) {
  const events = [];
  const url = new URL(`${baseUrl.replace(/\/$/, '')}${path}`);
  const req = http.get(url, { headers }, (res) => {
    res.setEncoding('utf8');
    let buf = '';
    res.on('data', (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        for (const line of block.split('\n')) {
          if (!line.startsWith('data: ')) continue; // ': heartbeat' 등 주석 무시
          try {
            const ev = JSON.parse(line.slice(6));
            events.push(ev);
            onEvent?.(ev);
          } catch { /* 파싱 실패 무시 */ }
        }
      }
    });
  });
  req.on('error', () => {}); // close 시 에러 무시
  return { events, close: () => req.destroy() };
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
