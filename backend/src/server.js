import dotenv from 'dotenv';
import app from './app.js';

dotenv.config();

const port = Number(process.env.PORT || 4000);
const host = process.env.HOST || '0.0.0.0';
app.listen(port, host, () => {
  console.log(`OOJOO FARM backend on http://${host}:${port}`);
  console.log(`Emulator URL: http://10.0.2.2:${port}/`);
});
