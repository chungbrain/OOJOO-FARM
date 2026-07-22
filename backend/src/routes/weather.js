import { Router } from 'express';
import db from '../db.js';
import { computeWateringFactor } from '../lib/wateringFactor.js';
const r = Router();

const GEO_URL = 'https://geocoding-api.open-meteo.com/v1/search';
const REVERSE_URL = 'https://geocoding-api.open-meteo.com/v1/reverse';
const WEATHER_URL = 'https://api.open-meteo.com/v1/forecast';

const geoCache = new Map();

async function geocode(region) {
  if (geoCache.has(region)) return geoCache.get(region);
  const url = `${GEO_URL}?name=${encodeURIComponent(region)}&count=1&language=ko`;
  const resp = await fetch(url);
  const data = await resp.json();
  if (!data.results || data.results.length === 0) return null;
  const { latitude, longitude, name, admin1, country } = data.results[0];
  const coords = {
    lat: latitude,
    lon: longitude,
    label: [name, admin1, country].filter(Boolean).join(', '),
  };
  geoCache.set(region, coords);
  return coords;
}

async function reverseGeocode(lat, lon) {
  const key = `rev:${lat.toFixed(2)},${lon.toFixed(2)}`;
  if (geoCache.has(key)) return geoCache.get(key);

  // 1) BigDataCloud (API key 불필요, 클라이언트용)
  try {
    const url = `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${lat}&longitude=${lon}&localityLanguage=ko`;
    const resp = await fetch(url);
    if (resp.ok) {
      const data = await resp.json();
      // 시/구/동/부근 형식으로 label 구성
      // BigDataCloud: city(동/읍), principalSubdivision(시/도), locality(구), countryName
      const parts = [];
      // 시/도 (예: 경기도, 서울특별시)
      if (data.principalSubdivision) parts.push(data.principalSubdivision);
      // 구 (예: 용인시 처인구 — locality가 구 단위인 경우)
      if (data.locality && data.locality !== data.city) parts.push(data.locality);
      // 동/읍 (예: 동백동)
      if (data.city) parts.push(data.city);
      // 부근 표시
      const short = data.city || data.locality || data.principalSubdivision || data.countryName;
      const label = parts.length > 0 ? parts.join(' ') + ' 부근' : short;
      if (short) {
        const value = { label, short };
        geoCache.set(key, value);
        return value;
      }
    }
  } catch (_) { /* fall through */ }

  // 2) Open-Meteo reverse (지원되는 경우)
  try {
    const url = `${REVERSE_URL}?latitude=${lat}&longitude=${lon}&language=ko&count=1`;
    const resp = await fetch(url);
    if (resp.ok) {
      const data = await resp.json();
      const hit = data.results?.[0];
      if (hit) {
        const label = [hit.name, hit.admin1, hit.country].filter(Boolean).join(', ');
        const short = hit.name || hit.admin1 || label;
        const value = { label, short };
        geoCache.set(key, value);
        return value;
      }
    }
  } catch (_) { /* fall through */ }

  const fallback = {
    short: `${lat.toFixed(2)}, ${lon.toFixed(2)}`,
    label: `${lat.toFixed(2)}, ${lon.toFixed(2)}`,
  };
  geoCache.set(key, fallback);
  return fallback;
}

function readCache(region) {
  const cached = db.prepare('SELECT * FROM weather_cache WHERE region=?').get(region);
  if (!cached?.updated_at) return null;
  const iso = cached.updated_at.replace(' ', 'T') + 'Z';
  const ageMin = (Date.now() - new Date(iso).getTime()) / 60000;
  if (ageMin >= 30) return { ...cached, stale: true };
  return { ...cached, stale: false };
}

function writeCache(region, temp, humidity, precipitation, weatherCode) {
  db.prepare(`INSERT INTO weather_cache(region, temp, humidity, precipitation, weather_code, updated_at) VALUES(?,?,?,?,?,datetime('now'))
    ON CONFLICT(region) DO UPDATE SET temp=excluded.temp, humidity=excluded.humidity, precipitation=excluded.precipitation, weather_code=excluded.weather_code, updated_at=datetime('now')`)
    .run(region, temp, humidity, precipitation, weatherCode);
}

async function fetchCurrentWeather(lat, lon) {
  // 1) Open-Meteo
  try {
    const url = `${WEATHER_URL}?latitude=${lat}&longitude=${lon}&current=temperature_2m,relative_humidity_2m,precipitation,weather_code`;
    const resp = await fetch(url);
    if (resp.ok) {
      const data = await resp.json();
      const c = data.current;
      if (c) {
        return {
          temp: c.temperature_2m,
          humidity: c.relative_humidity_2m,
          precipitation: c.precipitation,
          weatherCode: c.weather_code,
        };
      }
    }
  } catch (_) { /* fall through */ }

  // 2) wttr.in
  try {
    const url = `https://wttr.in/${lat},${lon}?format=j1`;
    const resp = await fetch(url, { headers: { 'User-Agent': 'oojoo-farm/1.0' } });
    if (resp.ok) {
      const data = await resp.json();
      const c = data.current_condition?.[0];
      if (c) {
        const codeMap = { 113: 0, 116: 2, 119: 3, 122: 3, 176: 61, 200: 95, 263: 51, 266: 51, 293: 61, 296: 61, 302: 63, 308: 65, 353: 80, 356: 81 };
        const wwo = Number(c.weatherCode);
        return {
          temp: Number(c.temp_C),
          humidity: Number(c.humidity),
          precipitation: Number(c.precipMM) || 0,
          weatherCode: codeMap[wwo] ?? 0,
        };
      }
    }
  } catch (_) { /* fall through */ }

  // 3) MET Norway
  try {
    const url = `https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${lat}&lon=${lon}`;
    const resp = await fetch(url, { headers: { 'User-Agent': 'oojoo-farm/1.0 github.com/oojoo-farm' } });
    if (resp.ok) {
      const data = await resp.json();
      const instant = data.properties?.timeseries?.[0]?.data?.instant?.details;
      const next1 = data.properties?.timeseries?.[0]?.data?.next_1_hours;
      if (instant) {
        return {
          temp: instant.air_temperature,
          humidity: instant.relative_humidity,
          precipitation: next1?.details?.precipitation_amount ?? 0,
          weatherCode: (next1?.details?.precipitation_amount ?? 0) > 0 ? 61 : 0,
        };
      }
    }
  } catch (_) { /* fall through */ }

  throw new Error('all weather providers failed');
}

function toResponse(region, w, extra = {}) {
  const weatherFactor = computeWateringFactor(w.temp, w.humidity, w.precipitation);
  return {
    region,
    temp: w.temp,
    humidity: w.humidity,
    precipitation: w.precipitation,
    weatherCode: w.weatherCode ?? w.weather_code,
    weatherFactor,
    ...extra,
  };
}

// GET /api/weather/coords?lat=&lon=  — GPS/자동 위치용 (반드시 /:region 보다 먼저)
r.get('/coords', async (req, res) => {
  const lat = parseFloat(req.query.lat);
  const lon = parseFloat(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon required' });
  }

  let place;
  try {
    place = await reverseGeocode(lat, lon);
  } catch {
    place = { short: `${lat.toFixed(2)},${lon.toFixed(2)}`, label: `${lat.toFixed(2)}, ${lon.toFixed(2)}` };
  }
  const region = place.short;

  const cached = readCache(region);
  if (cached && !cached.stale) {
    return res.json(toResponse(region, cached, { cached: true, label: place.label, lat, lon }));
  }

  try {
    const w = await fetchCurrentWeather(lat, lon);
    writeCache(region, w.temp, w.humidity, w.precipitation, w.weatherCode);
    res.json(toResponse(region, w, { cached: false, label: place.label, lat, lon }));
  } catch (e) {
    if (cached) return res.json(toResponse(region, cached, { cached: true, stale: true, label: place.label, lat, lon }));
    res.status(502).json({ error: 'weather fetch failed', detail: e.message });
  }
});

// GET /api/weather/:region — 지역명 조회 (캐시 30분)
r.get('/:region', async (req, res) => {
  const region = decodeURIComponent(req.params.region);
  if (!region || region === 'undefined' || region === 'coords') {
    return res.status(400).json({ error: 'region required' });
  }

  const cached = readCache(region);
  if (cached && !cached.stale) {
    return res.json(toResponse(region, cached, { cached: true }));
  }

  try {
    const coords = await geocode(region);
    if (!coords) return res.status(404).json({ error: 'region not found' });

    const w = await fetchCurrentWeather(coords.lat, coords.lon);
    writeCache(region, w.temp, w.humidity, w.precipitation, w.weatherCode);
    res.json(toResponse(region, w, { cached: false, label: coords.label, lat: coords.lat, lon: coords.lon }));
  } catch (e) {
    if (cached) return res.json(toResponse(region, cached, { cached: true, stale: true }));
    res.status(502).json({ error: 'weather fetch failed', detail: e.message });
  }
});

export default r;
