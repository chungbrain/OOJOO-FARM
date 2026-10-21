#!/usr/bin/env python3
"""F2 — PestNet (pest.bin) 학습.

해충발생 전용 초경량 CNN — 건강등급 판단(health_grade.bin)과 완전히 별도 모델.
4단계(해충없음/경미/보통/심각) 평가. 학습 장면은 다양한 건강등급 배경 위에
해충 증상(식해 구멍/검은 벌레 덩어리)을 랜덤 조합해, 황변·시듦(등급 신호)과
해충 증상을 독립적으로 구분하도록 한다. 자세한 스펙: docs/farmer-features.md
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
    PEST_LABELS,
    PEST_KO,
    paint48,
    train_model,
    save_bin,
    export_vectors,
)

ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
METRICS_DIR = ROOT / "ml" / "plant_health" / "metrics"
VECTOR_PATH = ROOT / "simulation" / "test" / "vectors_pest.json"

# 해충 학습용 건강등급 혼합 분포 — 등급과 해충의 독립성 학습
GRADE_P = [0.20, 0.40, 0.25, 0.10, 0.05]


def dataset(per_species: int, rng: np.random.Generator):
    """8종 x 4단계 x per_species. 등급은 혼합 배경으로 등장."""
    xs, ys = [], []
    for spec_id, spec in SPECIES.items():
        for yi, pest_level in enumerate(PEST_LABELS):
            for _ in range(per_species):
                grade = str(rng.choice(GRADE_LABELS, p=GRADE_P))
                xs.append(paint48(spec, grade, pest_level, rng))
                ys.append(yi)
    x = np.stack(xs)
    y = np.array(ys, np.int64)
    idx = rng.permutation(len(y))
    return x[idx], y[idx]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-species", type=int, default=150, help="종당 단계별 학습 샘플 수")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--lr", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=23)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    # Windows 콘솔(cp1252) 인코딩 오류 방지를 위해 print 는 ASCII 만 사용
    print(f"[pest] dataset: 8 species x {len(PEST_LABELS)} levels x {args.per_species} (mixed grades)")
    x, y = dataset(args.per_species, rng)
    result = train_model(
        x, y, len(PEST_LABELS), [1.0, 1.15, 1.15, 1.3],
        args.epochs, args.lr, "pest", rng,
    )

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    save_bin(ASSET_DIR / "pest.bin", result["params"], len(PEST_LABELS))
    METRICS_DIR.mkdir(parents=True, exist_ok=True)
    (METRICS_DIR / "pest.json").write_text(json.dumps({
        "model": "pest",
        "labels": PEST_LABELS,
        "labels_ko": [PEST_KO[p] for p in PEST_LABELS],
        "val_acc": result["val_acc"],
        "confusion": result["cm"],
        "n_train": result["n_train"],
        "n_val": result["n_val"],
        "input": SIZE,
        "arch": "conv8-16-24 + GAP + dense4 (Jev급, OJP1) — health_grade 와 별도 모델",
        "train_recipe": "8종 혼합 + 등급 혼합 배경 + 해충 단계별 식해/벌레 증상 + 다중 스케일/경로(480x640·144x192·96x96 박스 다운샘플) + augment + 전역 노름 클리핑",
    }, indent=2), encoding="utf-8")

    # JS round-trip 검증 벡터 — 단계별 + 독립성 케이스(건강 나쁨+해충 없음 등)
    cases = [
        ("cactus", "healthy", "none"),
        ("basil", "fair", "light"),
        ("strawberry", "poor", "moderate"),
        ("pepper", "very_healthy", "severe"),
        ("zucchini", "very_poor", "none"),
        ("cherry_tomato", "poor", "none"),
    ]
    export_vectors(VECTOR_PATH, result["params"], "pest", PEST_LABELS, cases)

    print(json.dumps({
        "model": "pest",
        "val_acc": result["val_acc"],
        "out": str(ASSET_DIR / "pest.bin"),
        "vectors": str(VECTOR_PATH),
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
