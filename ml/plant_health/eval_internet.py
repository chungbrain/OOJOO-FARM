#!/usr/bin/env python3
"""Evaluate on-device OJP1 models on licensed internet test photos."""
from __future__ import annotations

import argparse
import json
import struct
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

from species import LABELS, SPECIES
from train import SIZE, conv_forward, pool_forward, softmax

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
TEST_DIR = Path(__file__).resolve().parent / "test_internet"
MANIFEST = TEST_DIR / "manifest.json"
OUT_JSON = Path(__file__).resolve().parent / "metrics" / "internet_eval.json"
OUT_MD = Path(__file__).resolve().parent / "metrics" / "internet_eval.md"


def load_ojp1(path: Path) -> dict:
    raw = path.read_bytes()
    if raw[:4] != b"OJP1":
        raise ValueError(f"bad magic: {path}")
    size, nclass, nlayers = struct.unpack_from("<III", raw, 4)
    off = 16
    p = {}
    conv_i = 1
    for _ in range(nlayers):
        typ = raw[off]
        off += 1
        if typ == 1:
            kh, kw, cin, cout = struct.unpack_from("<IIII", raw, off)
            off += 16
            n_w = kh * kw * cin * cout
            w = np.frombuffer(raw, dtype="<f4", count=n_w, offset=off).reshape(kh, kw, cin, cout).copy()
            off += n_w * 4
            b = np.frombuffer(raw, dtype="<f4", count=cout, offset=off).copy()
            off += cout * 4
            p[f"c{conv_i}w"] = w
            p[f"c{conv_i}b"] = b
            conv_i += 1
        elif typ == 2:
            inn, out = struct.unpack_from("<II", raw, off)
            off += 8
            w = np.frombuffer(raw, dtype="<f4", count=inn * out, offset=off).reshape(inn, out).copy()
            off += inn * out * 4
            b = np.frombuffer(raw, dtype="<f4", count=out, offset=off).copy()
            p["dw"] = w
            p["db"] = b
        else:
            raise ValueError(f"unknown layer {typ}")
    p["_size"] = size
    p["_nclass"] = nclass
    return p


def predict_one(x: np.ndarray, p: dict) -> tuple[int, float]:
    h1, _ = conv_forward(x, p["c1w"], p["c1b"])
    p1, _ = pool_forward(h1)
    h2, _ = conv_forward(p1, p["c2w"], p["c2b"])
    p2, _ = pool_forward(h2)
    h3, _ = conv_forward(p2, p["c3w"], p["c3b"])
    p3, _ = pool_forward(h3)
    g = p3.mean(axis=(1, 2))
    prob = softmax(g @ p["dw"] + p["db"])[0]
    idx = int(prob.argmax())
    return idx, float(prob[idx])


def load_rgb48(path: Path) -> np.ndarray:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    im = im.crop((left, top, left + side, top + side)).resize((SIZE, SIZE), Image.Resampling.BILINEAR)
    arr = np.asarray(im, dtype=np.float32) / 255.0
    return arr[None, ...]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", choices=["all", "test", "train"], default="test")
    ap.add_argument("--tag", default="internet_eval")
    args = ap.parse_args()
    if not MANIFEST.exists():
        raise SystemExit(f"missing {MANIFEST}; run fetch_test_images.py first")
    man = json.loads(MANIFEST.read_text(encoding="utf-8"))
    allow = None
    split_path = TEST_DIR / "split.json"
    if args.split != "all" and split_path.exists():
        allow = set(json.loads(split_path.read_text(encoding="utf-8"))[args.split])
    out_json = Path(__file__).resolve().parent / "metrics" / f"{args.tag}.json"
    out_md = Path(__file__).resolve().parent / "metrics" / f"{args.tag}.md"
    models = {}
    rows = []
    cm = {sid: np.zeros((6, 6), np.int32) for sid in SPECIES}
    counts = defaultdict(lambda: defaultdict(int))
    correct = defaultdict(lambda: defaultdict(int))

    for item in man["items"]:
        if allow is not None and item["file"] not in allow:
            continue
        sid = item["species"]
        label = item["label"]
        path = TEST_DIR / "images" / item["file"]
        if not path.exists():
            continue
        if sid not in models:
            bin_path = MODEL_DIR / f"{sid}.bin"
            if not bin_path.exists():
                continue
            models[sid] = load_ojp1(bin_path)
        try:
            x = load_rgb48(path)
        except OSError:
            continue
        pred_i, conf = predict_one(x, models[sid])
        pred = LABELS[pred_i]
        yt = LABELS.index(label)
        cm[sid][yt, pred_i] += 1
        counts[sid][label] += 1
        if pred == label:
            correct[sid][label] += 1
        rows.append(
            {
                "file": item["file"],
                "species": sid,
                "query_label": label,
                "pred": pred,
                "conf": round(conf, 4),
                "ok": pred == label,
                "query": item.get("query"),
                "source": item.get("source"),
                "license": item.get("license"),
            }
        )

    per_species = {}
    for sid in SPECIES:
        n = int(sum(counts[sid].values()))
        acc = float(sum(correct[sid].values()) / n) if n else 0.0
        per_label = {}
        for lab in LABELS:
            c = counts[sid][lab]
            per_label[lab] = {
                "n": c,
                "acc": (correct[sid][lab] / c) if c else None,
            }
        per_species[sid] = {
            "korean": SPECIES[sid]["ko"],
            "n": n,
            "acc": acc,
            "per_label": per_label,
            "confusion": cm[sid].tolist(),
        }
    n_all = len(rows)
    acc_all = float(sum(1 for r in rows if r["ok"]) / n_all) if n_all else 0.0
    report = {
        "note": "Query text is a weak label. CC photos from Openverse/Wikimedia/iNaturalist.",
        "split": args.split,
        "n": n_all,
        "acc": acc_all,
        "species": per_species,
        "predictions": rows,
    }
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(report, indent=2), encoding="utf-8")

    lines = [
        "# Internet photo test (CC / Openverse + Wikimedia)",
        "",
        "학습에는 쓰지 않은 공개 웹 사진으로 온디바이스 모델을 평가했습니다.",
        "검색어를 약한 라벨로 썼기 때문에 숫자는 상한에 가깝지 않고, 새 화분 일반화의 하한 추정에 가깝습니다.",
        "",
        f"- 전체 {n_all}장, query-label 일치율 **{acc_all:.1%}**",
        "",
        "| 식물 | 장수 | 일치율 | healthy | water_low | water_high | light_low | pest | heat |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for sid, rec in per_species.items():
        cells = []
        for lab in LABELS:
            info = rec["per_label"][lab]
            cells.append("—" if not info["n"] else f"{info['acc']:.0%}")
        lines.append(
            f"| {rec['korean']} | {rec['n']} | {rec['acc']:.1%} | " + " | ".join(cells) + " |"
        )
    lines += ["", "## 해석", ""]
    lines += [
        "- `healthy`가 웹에서 가장 신뢰할 수 있는 클래스입니다. 예쁜 식물 사진이 많기 때문입니다.",
        "- `water_low` / `heat` / `light_low` 검색 결과는 병해·품종 사진과 섞여 약한 라벨입니다.",
        "- 한 클래스로 전부 쏠리면 화분 일반화가 아니라 합성 단서 과적합입니다.",
        "",
    ]
    out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"n": n_all, "acc": acc_all, "split": args.split, "json": str(out_json), "md": str(out_md)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
