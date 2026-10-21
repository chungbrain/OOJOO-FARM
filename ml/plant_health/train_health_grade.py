#!/usr/bin/env python3
"""F1 — JevHealthNet (health_grade.bin) 학습.

식물 건강등급 5단계(매우건강/건강/보통/나쁨/매우나쁨) 범용 초경량 CNN.
8종 혼합 학습으로 종 무관하게 등급 판단. 해충 증상은 배경 노이즈
(light 10% / moderate 3%)로만 포함해, 등급 판단이 해충 전용 모델(pest.bin)과
독립적으로 동작하도록 한다. 자세한 스펙: docs/farmer-features.md
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

from species import SPECIES
from grade_paint import (
    SIZE,
    GRADE_LABELS,
    GRADE_KO,
    PEST_LABELS,
    paint48,
    train_model,
    save_bin,
    export_vectors,
)

ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
METRICS_DIR = ROOT / "ml" / "plant_health" / "metrics"
VECTOR_PATH = ROOT / "simulation" / "test" / "vectors_health_grade.json"


def dataset(per_species: int, rng: np.random.Generator):
    """8종 x 5등급 x per_species. 등급별 해충은 노이즈 수준으로만."""
    xs, ys = [], []
    for spec_id, spec in SPECIES.items():
        for yi, grade in enumerate(GRADE_LABELS):
            for _ in range(per_species):
                r = rng.random()
                pest_level = "light" if r < 0.10 else ("moderate" if r < 0.13 else "none")
                xs.append(paint48(spec, grade, pest_level, rng))
                ys.append(yi)
    x = np.stack(xs)
    y = np.array(ys, np.int64)
    idx = rng.permutation(len(y))
    return x[idx], y[idx]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-species", type=int, default=120, help="종당 등급별 학습 샘플 수")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--lr", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    print(f"[health_grade] dataset: 8 species x {len(GRADE_LABELS)} grades x {args.per_species}")
    x, y = dataset(args.per_species, rng)
    # 후순위 등급(나쁨/매우나쁨) 알림 중요도를 반영해 가중
    result = train_model(
        x, y, len(GRADE_LABELS), [1.0, 1.0, 1.1, 1.3, 1.5],
        args.epochs, args.lr, "health_grade", rng,
    )

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    save_bin(ASSET_DIR / "health_grade.bin", result["params"], len(GRADE_LABELS))
    METRICS_DIR.mkdir(parents=True, exist_ok=True)
    (METRICS_DIR / "health_grade.json").write_text(json.dumps({
        "model": "health_grade",
        "labels": GRADE_LABELS,
        "labels_ko": [GRADE_KO[g] for g in GRADE_LABELS],
        "val_acc": result["val_acc"],
        "confusion": result["cm"],
        "n_train": result["n_train"],
        "n_val": result["n_val"],
        "input": SIZE,
        "arch": "conv8-16-24 + GAP + dense5 (Jev급, OJP1)",
        "train_recipe": "8종 혼합 등급별 합성 + 해충 노이즈 13% + 다중 스케일/경로(480x640·144x192·96x96 박스 다운샘플) + augment + 전역 노름 클리핑",
    }, indent=2), encoding="utf-8")

    # JS round-trip 검증 벡터 — 5등급 각 1 case (해충 없음 기준)
    species_ids = list(SPECIES)
    cases = [(species_ids[i % len(species_ids)], g, "none") for i, g in enumerate(GRADE_LABELS)]
    export_vectors(VECTOR_PATH, result["params"], "health_grade", GRADE_LABELS, cases)

    print(json.dumps({
        "model": "health_grade",
        "val_acc": result["val_acc"],
        "out": str(ASSET_DIR / "health_grade.bin"),
        "vectors": str(VECTOR_PATH),
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
