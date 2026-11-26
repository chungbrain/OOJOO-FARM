// 서버(GCP 백엔드) 연결 — 현장 성능·farmer 사진 조회, 모델 버전 업로드·배포.
import fs from 'node:fs';
import * as store from './store.js';

export function remote(cfg) {
  const base = String(cfg.serverUrl || '').replace(/\/+$/, '');
  const auth = cfg.token ? { 'x-admin-token': cfg.token } : {};
  async function req(method, p, { json, body, timeout = 8000 } = {}) {
    if (!base) throw Object.assign(new Error('서버 주소가 설정되지 않았습니다'), { status: 400 });
    const headers = { ...auth };
    if (json !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
    let res;
    try {
      res = await fetch(base + p, { method, headers, body, signal: AbortSignal.timeout(timeout) });
    } catch (e) {
      throw Object.assign(new Error(`서버에 연결할 수 없습니다 (${e.name === 'TimeoutError' ? '시간 초과' : e.message})`), { status: 502 });
    }
    const text = await res.text();
    let data; try { data = JSON.parse(text); } catch { data = { error: text.slice(0, 200) }; }
    if (!res.ok) throw Object.assign(new Error(data.error || `서버 오류 ${res.status}`), { status: res.status >= 500 ? 502 : res.status });
    return data;
  }
  async function bytes(p, timeout = 20000) {
    const res = await fetch(base + p, { headers: auth, signal: AbortSignal.timeout(timeout) });
    if (!res.ok) throw Object.assign(new Error(`다운로드 실패 ${res.status}: ${p}`), { status: 502 });
    return Buffer.from(await res.arrayBuffer());
  }
  return {
    base,
    get: (p, o) => req('GET', p, o),
    post: (p, json, o) => req('POST', p, { ...o, json }),
    postForm: (p, form, o) => req('POST', p, { ...o, body: form }),
    bytes,
  };
}

/** 모델 정의 — 서버 업로드 manifest 용 */
function modelManifest(m) {
  return {
    id: m.id, kind: m.kind, icon: m.icon, name: m.name, nameEn: m.nameEn,
    description: m.description, descriptionEn: m.descriptionEn,
    labels: m.labels, aliases: m.aliases || [], baseModel: m.baseModel || null,
  };
}

/**
 * 버전 하나를 서버에 올린다: 모델 파일 + 라벨 + 지표 + 그 버전의 학습·검증 사진(서버에 없는 것만).
 * activate=true 면 올린 뒤 바로 배포한다.
 */
export async function uploadVersion(cfg, id, n, { activate = false } = {}) {
  const m = store.getModel(id);
  const v = store.getVersion(id, n);
  if (!m || !v) throw Object.assign(new Error('버전이 없습니다'), { status: 404 });
  if (v.source === 'bundled') throw Object.assign(new Error('v1 은 앱 내장 모델이라 서버에 이미 있습니다'), { status: 400 });
  if (!cfg.token) throw Object.assign(new Error('업로드 토큰이 설정되지 않았습니다 (설정에서 서버 토큰 입력)'), { status: 400 });
  const r = remote(cfg);
  const bytes = fs.readFileSync(store.versionModelPath(id, v));
  const dataset = v.dataset || [];
  const shas = [...new Set(dataset.map((d) => d.sha256))];
  const missing = [];
  for (let i = 0; i < shas.length; i += 500) {
    missing.push(...(await r.post(`/api/models/${id}/datasets/missing`, { shas: shas.slice(i, i + 500) })).missing);
  }
  const form = new FormData();
  form.append('manifest', JSON.stringify({
    model: modelManifest(m),
    version: {
      version: v.version, parentVersion: v.parentVersion, labels: v.labels, sha256: v.sha256,
      metrics: v.metrics, baseline: v.baseline, train: v.train, createdAt: v.createdAt,
    },
    dataset,
  }));
  form.append('model', new Blob([bytes], { type: 'application/octet-stream' }), `${id}-v${n}.bin`);
  for (const sha of missing) {
    const p = store.samplePath(id, sha);
    if (!fs.existsSync(p)) throw Object.assign(new Error(`학습 사진 파일이 없습니다: ${sha.slice(0, 12)}…`), { status: 409 });
    form.append('samples', new Blob([fs.readFileSync(p)], { type: 'image/jpeg' }), `${sha}.jpg`);
  }
  const res = await r.postForm(`/api/models/${id}/releases`, form, { timeout: 180000 });
  let activated = null;
  if (activate) activated = await r.post(`/api/models/${id}/versions/${n}/activate`, {});
  return { ...res, uploadedPhotos: missing.length, datasetSize: dataset.length, activated: activated?.ok ? activated : null };
}
