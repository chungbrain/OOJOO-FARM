import { Router } from 'express';
import db from '../db.js';
import { computeWateringFactor } from '../lib/wateringFactor.js';
const r = Router();

const GEO_URL = 'https://geocoding-api.open-meteo.com/v1/search';
const WEATHER_URL = 'https://api.open-meteo.com/v1/forecast';

// 지역명 → 위도/경도 (캐싱 메모리)
const geoCache = new Map();

async function geocode(region) {
  if (geoCache.has(region)) return geoCache.get(region);
  const url = `${GEO_URL}?name=${encodeURIComponent(region)}&count=1&language=ko`;
  const resp = await fetch(url);
  const data = await resp.json();
  if (!data.results || data.results.length === 0) return null;
  const { latitude, longitude } = data.results[0];
  const coords = { lat: latitude, lon: longitude };
  geoCache.set(region, coords);
  return coords;
}

// GET /api/weather/:region — 지역 날씨 조회 (캐시 30분)
r.get('/:region', async (req, res) => {
  const region = decodeURIComponent(req.params.region);
  if (!region || region === 'undefined') return res.status(400).json({ error: 'region required' });

  const cached = db.prepare('SELECT * FROM weather_cache WHERE region=?').get(region);
  if (cached && cached.updated_at) {
    // SQLite datetime('now') 는 'YYYY-MM-DD HH:MM:SS'(UTC, 공백 구분) 형식이므로
    // ISO 로 변환해 안정적으로 파싱한다.
    const iso = cached.updated_at.replace(' ', 'T') + 'Z';
    const ageMin = (Date.now() - new Date(iso).getTime()) / 60000;
    if (ageMin < 30) {
      return res.json({ region, ...cached, cached: true });
    }
  }

  try {
    const coords = await geocode(region);
    if (!coords) return res.status(404).json({ error: 'region not found' });

    const url = `${WEATHER_URL}?latitude=${coords.lat}&longitude=${coords.lon}&current=temperature_2m,relative_humidity_2m,precipitation,weather_code`;
    const resp = await fetch(url);
    const data = await resp.json();
    const c = data.current;
    const temp = c.temperature_2m;
    const humidity = c.relative_humidity_2m;
    const precipitation = c.precipitation;
    const weatherCode = c.weather_code;

    db.prepare(`INSERT INTO weather_cache(region, temp, humidity, precipitation, weather_code, updated_at) VALUES(?,?,?,?,?,datetime('now'))
      ON CONFLICT(region) DO UPDATE SET temp=excluded.temp, humidity=excluded.humidity, precipitation=excluded.precipitation, weather_code=excluded.weather_code, updated_at=datetime('now')`)
      .run(region, temp, humidity, precipitation, weatherCode);

    const weatherFactor = computeWateringFactor(temp, humidity, precipitation);

    res.json({ region, temp, humidity, precipitation, weatherCode, weatherFactor, cached: false });
  } catch (e) {
    if (cached) return res.json({ region, ...cached, cached: true, stale: true });
    res.status(502).json({ error: 'weather fetch failed', detail: e.message });
  }
});

export default r;
