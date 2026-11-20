#!/usr/bin/env python3
"""종별 on-device 모델 round-trip 벡터 생성 — device==PC 추론 보증용.

assets/models/<species>.bin (OJP1) 을 Python 에서 파싱/forward 한
logits 를 고정 입력과 함께 simulation/test/vectors_species.json 에 쓴다.
시뮬레이션(src/models/ojp1.js) 이 동일 .bin 으로 동일 logits(±1e-3) 을
내야 하는 node:test 가 이 파일을 검증한다.
"""
from __future__ import annotations

import base64
import json
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from species import SPECIES
from train import SIZE, conv_forward, pool_forward, paint_plant

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ASSETS = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
OUT = ROOT / "simulation" / "test" / "vectors_species.json"
TOL = 1e-3


def parse_bin(path: Path):
    b = path.read_bytes()
    if b[:4] != b"OJP1":
        raise SystemExit(f"bad magic: {path}")
    off = 4
    size, nclass, nlay = struct.unpack_from("<III", b, off)
    off += 12
    layers = []
    for _ in range(nlay):
        t = b[off]
        off += 1
        if t == 1:
            kh, kw, cin, cout = struct.unpack_from("<IIII", b, off)
            off += 16
            n = kh * kw * cin * cout
            w = np.frombuffer(b, dtype="<f4", count=n, offset=off).reshape(kh, kw, cin, cout).copy()
            off += n * 4
            bb = np.frombuffer(b, dtype="<f4", count=cout, offset=off).copy()
            off += cout * 4
            layers.append(("conv", w, bb))
        else:
            inn, out = struct.unpack_from("<II", b, off)
            off += 8
            w = np.frombuffer(b, dtype="<f4", count=inn * out, offset=off).reshape(inn, out).copy()
            off += inn * out * 4
            bb = np.frombuffer(b, dtype="<f4", count=out, offset=off).copy()
            off += out * 4
            layers.append(("dense", w, bb))
    if off != len(b):
        raise SystemExit(f"trailing bytes: {path}")
    return size, nclass, layers


def ojp1_forward(layers, x: np.ndarray) -> np.ndarray:
    """x: (SIZE,SIZE,3) 0..1 — Ojp1.kt forward 와 동일 연산."""
    n = x[None].astype(np.float32)
    for kind, w, b in layers:
        if kind == "conv":
            n, _ = conv_forward(n, w, b)
            n, _ = pool_forward(n)
        else:
            gap = n[0].mean(axis=(0, 1))
            return (gap @ w + b).astype(np.float64)
    raise SystemExit("no dense layer")


def main() -> int:
    out: dict = {
        "generated_by": "make_vectors.py",
        "tolerance": TOL,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "species": {},
    }
    for si, sid in enumerate(SPECIES):
        bin_path = ASSETS / f"{sid}.bin"
        if not bin_path.exists():
            print(f"[vectors] SKIP {sid}: {bin_path} 없음")
            continue
        size, nclass, layers = parse_bin(bin_path)
        assert size == SIZE and nclass == 6, f"{sid}: size={size} nclass={nclass}"
        # 고정 입력: 해당 종의 healthy 이미지 (시드 고정)
        rng = np.random.default_rng([42, si])
        x = paint_plant(SPECIES[sid], "healthy", rng)
        logits = ojp1_forward(layers, x)
        b64 = base64.b64encode(np.ascontiguousarray(x, dtype="<f4").tobytes()).decode()
        out["species"][sid] = {
            "korean": SPECIES[sid]["ko"],
            "label": "healthy",
            "input": b64,
            "logits": [float(v) for v in logits],
        }
        print(f"[vectors] {sid}: {len(logits)} logits")
    OUT.write_text(json.dumps(out), encoding="utf-8")
    print(f"[vectors] {len(out['species'])}종 -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
