import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import pairing from './routes/pairing.js';
import plants from './routes/plants.js';
import events from './routes/events.js';
import watering from './routes/watering.js';
import './db.js';

dotenv.config();
const app = express();
app.use(cors());
app.use(express.json());

app.get('/health', (req, res) => res.json({ ok: true, service: 'oojoo-farm', ts: Date.now() }));

app.use('/api/pairing', pairing);
app.use('/api/plants', plants);
app.use('/api/events', events);
app.use('/api/watering', watering);

app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'internal' });
});

const port = process.env.PORT || 4000;
app.listen(port, () => console.log(`OOJOO FARM backend on :${port}`));
