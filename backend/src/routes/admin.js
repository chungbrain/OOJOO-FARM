import { Router } from 'express';
import db from '../db.js';

const r = Router();

r.get('/stats', (req, res) => {
  try {
    // 1. Device Count
    const usersCount = db.prepare("SELECT COUNT(*) as count FROM users").get().count;
    
    // slaves online >= 1 OR last_seen within 24h
    const activeSlaves = db.prepare("SELECT COUNT(*) as count FROM slaves WHERE online = 1 OR last_seen > datetime('now', '-1 day')").get().count;
    
    // 2. Feature Usage (API calls grouped by base path)
    const apiLogs = db.prepare(`
      SELECT 
        substr(path, 1, instr(substr(path, 6), '/') + 4) as endpoint, 
        COUNT(*) as count 
      FROM api_logs 
      WHERE path LIKE '/api/%'
      GROUP BY endpoint 
      ORDER BY count DESC 
      LIMIT 10
    `).all();

    const formattedApiLogs = apiLogs.map(row => {
      // Fix endpoint grouping if no trailing slash exists
      let ep = row.endpoint;
      if (ep === '/api' || !ep) {
        ep = row.path ? row.path.split('/').slice(0, 3).join('/') : '/api/unknown';
      }
      return { endpoint: ep, count: row.count };
    });

    // 3. Recent Crashes
    const recentCrashes = db.prepare(`
      SELECT id, source, device_id, error_message, created_at 
      FROM crash_logs 
      ORDER BY created_at DESC 
      LIMIT 20
    `).all();

    // 4. Time series for API calls (Last 24h by hour)
    const timeSeries = db.prepare(`
      SELECT strftime('%Y-%m-%d %H:00', created_at) as hour, COUNT(*) as count 
      FROM api_logs 
      WHERE created_at > datetime('now', '-1 day')
      GROUP BY hour 
      ORDER BY hour ASC
    `).all();

    res.json({
      devices: {
        users: usersCount,
        activeSlaves: activeSlaves
      },
      apiUsage: formattedApiLogs,
      crashes: recentCrashes,
      timeSeries: timeSeries
    });
  } catch (error) {
    console.error('Admin stats error:', error);
    res.status(500).json({ error: 'Internal Server Error' });
  }
});

export default r;
