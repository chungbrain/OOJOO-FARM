#!/usr/bin/env python3
"""Stratified train/test split for internet photos. Test is never used in training."""
from __future__ import annotations

import json
import random
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "test_internet"
MANIFEST = ROOT / "manifest.json"
SPLIT = ROOT / "split.json"


def main() -> int:
    items = json.loads(MANIFEST.read_text(encoding="utf-8"))["items"]
    rng = random.Random(7)
    groups = defaultdict(list)
    for it in items:
        groups[(it["species"], it["label"])].append(it)
    train, test = [], []
    for rows in groups.values():
        rows = list(rows)
        rng.shuffle(rows)
        if len(rows) == 1:
            test.append(rows[0]["file"])
        elif len(rows) == 2:
            train.append(rows[0]["file"])
            test.append(rows[1]["file"])
        else:
            n_test = max(1, len(rows) // 4)
            test.extend(r["file"] for r in rows[:n_test])
            train.extend(r["file"] for r in rows[n_test:])
    payload = {"train": sorted(train), "test": sorted(test), "n_train": len(train), "n_test": len(test)}
    SPLIT.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps({"n_train": payload["n_train"], "n_test": payload["n_test"], "split": str(SPLIT)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
