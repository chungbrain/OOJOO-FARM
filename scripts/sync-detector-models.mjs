#!/usr/bin/env node
// 온디바이스 검출 모듈 모델을 서버 배포 폴더로 복사한다.
//   android/slave/app/src/main/assets/models/*.bin  →  backend/models/*.bin
// ml/plant_health 학습 스크립트는 앱 assets 에 모델을 쓴다. 서버 카탈로그(/api/detectors)는
// backend/models 를 배포하므로, 모델을 다시 학습했으면 이 스크립트로 맞춘 뒤 커밋한다.
// (backend/test/detectors.test.js 가 두 폴더의 불일치를 잡는다)
//
// 사용: node scripts/sync-detector-models.mjs [--check]
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'android/slave/app/src/main/assets/models');
const DST = path.join(ROOT, 'backend/models');
const check = process.argv.includes('--check');

const sha = (f) => createHash('sha256').update(fs.readFileSync(f)).digest('hex');
fs.mkdirSync(DST, { recursive: true });

let changed = 0;
for (const name of fs.readdirSync(SRC).filter((n) => n.endsWith('.bin')).sort()) {
  const src = path.join(SRC, name);
  const dst = path.join(DST, name);
  if (fs.existsSync(dst) && sha(src) === sha(dst)) continue;
  changed++;
  if (check) {
    console.error(`out of sync: ${name}`);
  } else {
    fs.copyFileSync(src, dst);
    console.log(`copied ${name} (${sha(src).slice(0, 12)})`);
  }
}
if (check && changed) process.exit(1);
console.log(changed ? `${changed} model(s) ${check ? 'out of sync' : 'updated'}` : 'models in sync');
