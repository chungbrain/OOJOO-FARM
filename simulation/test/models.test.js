// OJP1 모델 런타임 round-trip 검증 — Python(ml/plant_health) 이 export한
// 고정 입력/기대 logits 과 JS forward 결과가 일치(허용오차 1e-3)해야 한다.
// Android(PlantHealthNet.kt)와 시뮬레이션이 동일 모델로 동일 추론을 함을 보장.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { forward, parseModel } from '../src/models/ojp1.js';
import * as jevHealth from '../src/models/jevHealth.js';
import * as pestNet from '../src/models/pestNet.js';

const dir = path.dirname(fileURLToPath(import.meta.url));

function loadVectors(file) {
  return JSON.parse(readFileSync(path.join(dir, file), 'utf8'));
}

function toRgb48(b64) {
  const buf = Buffer.from(b64, 'base64');
  assert.equal(buf.length, 48 * 48 * 3 * 4, '입력은 48x48x3 float32');
  return new Float32Array(buf.buffer, buf.byteOffset, 48 * 48 * 3);
}

test('OJP1 round-trip: JevHealthNet(health_grade.bin) — Python logits 일치', () => {
  const vectors = loadVectors('vectors_health_grade.json');
  assert.deepEqual(vectors.labels, jevHealth.LABELS);
  const model = jevHealth.loadModel();
  assert.equal(model.nclass, jevHealth.LABELS.length);
  assert.equal(model.size, 48);
  assert.equal(model.convs.length, 3);
  for (const c of vectors.cases) {
    const logits = forward(model, toRgb48(c.input));
    assert.equal(logits.length, c.logits.length);
    for (let i = 0; i < c.logits.length; i++) {
      assert.ok(
        Math.abs(logits[i] - c.logits[i]) < 1e-3,
        `${c.grade} logit[${i}] JS=${logits[i]} vs Python=${c.logits[i]}`
      );
    }
  }
});

test('OJP1 round-trip: PestNet(pest.bin) — Python logits 일치', () => {
  const vectors = loadVectors('vectors_pest.json');
  assert.deepEqual(vectors.labels, pestNet.LABELS);
  const model = pestNet.loadModel();
  assert.equal(model.nclass, pestNet.LABELS.length);
  for (const c of vectors.cases) {
    const logits = forward(model, toRgb48(c.input));
    for (let i = 0; i < c.logits.length; i++) {
      assert.ok(
        Math.abs(logits[i] - c.logits[i]) < 1e-3,
        `${c.pest} logit[${i}] JS=${logits[i]} vs Python=${c.logits[i]}`
      );
    }
  }
});

test('OJP1 파서: 헤더/레이어 구조 검증 + 불량 파일 거부', () => {
  const model = jevHealth.loadModel();
  // conv 3-8 / 8-16 / 16-24 + dense 24-nclass (Jev급 아키텍처)
  assert.deepEqual(model.convs.map((c) => [c.cin, c.cout]), [[3, 8], [8, 16], [16, 24]]);
  assert.equal(model.dense.inn, 24);
  assert.equal(model.dense.out, jevHealth.LABELS.length);
  assert.throws(() => parseModel(Buffer.from('NOTOJP1PADPADPADPADPAD')), /bad model magic/);
  assert.throws(() => parseModel(Buffer.alloc(8)), /too small|bad model/);
});

test('모델 파일 크기 — 초경량(Jev 등급) 기준 확인', () => {
  const repoRoot = path.resolve(dir, '../..');
  const hgSize = statSync(path.join(repoRoot, 'android/slave/app/src/main/assets/models/health_grade.bin')).size;
  const pestSize = statSync(path.join(repoRoot, 'android/slave/app/src/main/assets/models/pest.bin')).size;
  assert.ok(hgSize < 60 * 1024, `health_grade.bin ${hgSize}B — 60KB 미만`);
  assert.ok(pestSize < 50 * 1024, `pest.bin ${pestSize}B — 50KB 미만`);
});
