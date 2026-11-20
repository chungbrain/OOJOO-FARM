// 종별 OJP1 모델 검증 — 14종 asset 구조 + Python/JS round-trip.
// Android(PlantHealthNet.kt)과 시뮬레이션이 동일 .bin 으로 동일 추론을 함을 보장.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { forward, parseModel } from '../src/models/ojp1.js';

const dir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(dir, '../..');
const MODEL_DIR = path.join(repoRoot, 'android', 'slave', 'app', 'src', 'main', 'assets', 'models');
const SPECIES_EXPECT = 14;
const HEALTH_LABELS = ['healthy', 'water_low', 'water_high', 'light_low', 'pest', 'heat'];

function speciesBins() {
  return readdirSync(MODEL_DIR)
    .filter((f) => f.endsWith('.bin') && f !== 'health_grade.bin' && f !== 'pest.bin')
    .map((f) => f.slice(0, -4));
}

test('종별 모델: asset 14종 존재 + OJP1 구조(48x48, conv8-16-24+dense6) + forward 유한', () => {
  const bins = speciesBins();
  assert.ok(bins.length >= SPECIES_EXPECT, `14종 필요, ${bins.length}종: ${bins.join(',')}`);
  for (const id of bins) {
    const buf = readFileSync(path.join(MODEL_DIR, `${id}.bin`));
    const m = parseModel(buf);
    assert.equal(m.size, 48, `${id} size`);
    assert.equal(m.nclass, 6, `${id} nclass`);
    assert.deepEqual(m.convs.map((c) => [c.cin, c.cout]), [[3, 8], [8, 16], [16, 24]], `${id} convs`);
    assert.equal(m.dense.inn, 24, `${id} dense.inn`);
    assert.equal(m.dense.out, 6, `${id} dense.out`);
    assert.ok(buf.length < 60 * 1024, `${id} ${buf.length}B — 60KB 미만`);
    const logits = forward(m, new Float32Array(48 * 48 * 3));
    for (const v of logits) assert.ok(Number.isFinite(v), `${id} logit 유한`);
  }
});

test('종별 모델: labels.json = 6클래스 라벨', () => {
  const labels = JSON.parse(readFileSync(path.join(MODEL_DIR, 'labels.json'), 'utf8'));
  assert.deepEqual(labels, HEALTH_LABELS);
});

test('종별 모델: Python↔JS round-trip (vectors_species.json, ±1e-3)', () => {
  const vec = JSON.parse(readFileSync(path.join(dir, 'vectors_species.json'), 'utf8'));
  const ids = Object.keys(vec.species);
  assert.ok(ids.length >= SPECIES_EXPECT, `14종 필요, ${ids.length}종`);
  for (const id of ids) {
    const m = parseModel(readFileSync(path.join(MODEL_DIR, `${id}.bin`)));
    const bin = Buffer.from(vec.species[id].input, 'base64');
    assert.equal(bin.length, 48 * 48 * 3 * 4, `${id} 입력 크기`);
    const rgb = new Float32Array(bin.buffer, bin.byteOffset, bin.length / 4);
    const logits = forward(m, rgb);
    const expect = vec.species[id].logits;
    for (let i = 0; i < expect.length; i++) {
      assert.ok(
        Math.abs(logits[i] - expect[i]) < 1e-3,
        `${id} logit[${i}] JS=${logits[i]} vs Python=${expect[i]}`
      );
    }
  }
});
