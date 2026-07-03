import { Router } from 'express';
import db from '../db.js';
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
    const ageMin = (Date.now() - new Date(cached.updated_at + 'Z').getTime()) / 60000;
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

    // 관수량 가중치 계산: 기준량 × 온도가중치 × 습도가중치 × 강수보정
    let weatherFactor = 1.0;
    if (temp >= 28) weatherFactor *= 1.2 + Math.min(0.3, (temp - 28) * 0.05);
    else if (temp <= 15) weatherFactor *= 0.6 + Math.max(-0.2, (15 - temp) * 0.04);
    if (humidity >= 70) weatherFactor *= 0.7;
    if (precipitation > 0) weatherFactor *= 0.3;

    res.json({ region, temp, humidity, precipitation, weatherCode, weatherFactor: Math.round(weatherFactor * 100) / 100, cached: false });
  } catch (e) {
    if (cached) return res.json({ region, ...cached, cached: true, stale: true });
    res.status(502).json({ error: 'weather fetch failed', detail: e.message });
  }
});

export default r;
