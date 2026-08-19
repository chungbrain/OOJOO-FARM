#!/usr/bin/env python3
"""Fine-tune OJP1 models on internet train photos mixed with synthetic leaves."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from eval_internet import load_ojp1, load_rgb48
from species import LABELS, SPECIES
from train import (
    RNG,
    SIZE,
    augment,
    conv_backward,
    conv_forward,
    paint_plant,
    pool_backward,
    pool_forward,
    predict,
    save_bin,
    snapshot,
    softmax,
)

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
DATA = Path(__file__).resolve().parent / "test_internet"
MANIFEST = DATA / "manifest.json"
SPLIT = DATA / "split.json"
METRICS = Path(__file__).resolve().parent / "metrics" / "finetune_web.json"


def load_split_images(files: set[str], species: str) -> tuple[np.ndarray, np.ndarray]:
    man = json.loads(MANIFEST.read_text(encoding="utf-8"))
    xs, ys = [], []
    for item in man["items"]:
        if item["species"] != species or item["file"] not in files:
            continue
        path = DATA / "images" / item["file"]
        if not path.exists():
            continue
        try:
            xs.append(load_rgb48(path)[0])
            ys.append(LABELS.index(item["label"]))
        except OSError:
            continue
    if not xs:
        return np.zeros((0, SIZE, SIZE, 3), np.float32), np.zeros((0,), np.int64)
    return np.stack(xs).astype(np.float32), np.array(ys, np.int64)


def step(p: dict, xb, yb, lr: float) -> float:
    h1, c1 = conv_forward(xb, p["c1w"], p["c1b"])
    p1, s1 = pool_forward(h1)
    h2, c2 = conv_forward(p1, p["c2w"], p["c2b"])
    p2, s2 = pool_forward(h2)
    h3, c3 = conv_forward(p2, p["c3w"], p["c3b"])
    p3, s3 = pool_forward(h3)
    g = p3.mean(axis=(1, 2))
    logits = g @ p["dw"] + p["db"]
    prob = softmax(logits)
    loss = float(-np.log(np.clip(prob[np.arange(len(yb)), yb], 1e-7, 1)).mean())
    yoh = np.zeros_like(prob)
    yoh[np.arange(len(yb)), yb] = 1
    weights = np.array([2.0, 1.3, 1.5, 1.4, 1.4, 1.3], np.float32)[yb][:, None]
    dlog = weights * (prob - yoh) / len(yb)
    p["dw"] -= lr * (g.T @ dlog)
    p["db"] -= lr * dlog.sum(0)
    dg = dlog @ p["dw"].T
    dp3 = np.ones_like(p3) * dg[:, None, None, :] / (p3.shape[1] * p3.shape[2])
    dh3 = pool_backward(dp3, s3)
    dp2, dw3, db3 = conv_backward(dh3, c3)
    p["c3w"] -= lr * dw3
    p["c3b"] -= lr * db3
    dh2 = pool_backward(dp2, s2)
    dp1, dw2, db2 = conv_backward(dh2, c2)
    p["c2w"] -= lr * dw2
    p["c2b"] -= lr * db2
    dh1 = pool_backward(dp1, s1)
    _, dw1, db1 = conv_backward(dh1, c1)
    p["c1w"] -= lr * np.clip(dw1, -1, 1)
    p["c1b"] -= lr * np.clip(db1, -1, 1)
    return loss


def build_epoch(spec_id: str, web_x, web_y) -> tuple[np.ndarray, np.ndarray]:
    spec = SPECIES[spec_id]
    syn_x, syn_y = [], []
    for yi, lab in enumerate(LABELS):
        for _ in range(16):
            syn_x.append(paint_plant(spec, lab, RNG))
            syn_y.append(yi)
    xs = [augment(im, RNG) for im in syn_x]
    ys = list(syn_y)
    if len(web_x):
        for c in range(6):
            idx = np.where(web_y == c)[0]
            if len(idx) == 0:
                continue
            pick = RNG.choice(idx, size=20, replace=True)
            for i in pick:
                xs.append(augment(web_x[i], RNG))
                ys.append(int(c))
    x = np.stack(xs).astype(np.float32)
    y = np.array(ys, np.int64)
    perm = RNG.permutation(len(y))
    return x[perm], y[perm]


def finetune_one(spec_id: str, train_files: set[str], epochs: int, lr: float) -> dict:
    bin_path = MODEL_DIR / f"{spec_id}.bin"
    p = load_ojp1(bin_path)
    for k in ("_size", "_nclass"):
        p.pop(k, None)
    all_x, all_y = load_split_images(train_files, spec_id)
    val_idx, tr_idx = [], []
    for c in range(6):
        idx = np.where(all_y == c)[0]
        if len(idx) == 0:
            continue
        n_val = 1 if len(idx) >= 3 else 0
        val_idx.extend(idx[:n_val].tolist())
        tr_idx.extend(idx[n_val:].tolist())
    if tr_idx:
        web_x, web_y = all_x[tr_idx], all_y[tr_idx]
    else:
        web_x, web_y = all_x, all_y
    if val_idx:
        val_x, val_y = all_x[val_idx], all_y[val_idx]
    else:
        val_x, val_y = np.zeros((0, SIZE, SIZE, 3), np.float32), np.zeros((0,), np.int64)
    best = snapshot(p)
    best_acc = -1.0
    hist = []
    for ep in range(1, epochs + 1):
        xt, yt = build_epoch(spec_id, web_x, web_y)
        loss_acc, steps = 0.0, 0
        for i in range(0, len(yt), 16):
            loss_acc += step(p, xt[i:i + 16], yt[i:i + 16], lr)
            steps += 1
        if len(val_x):
            acc = float((predict(val_x, p) == val_y).mean())
        else:
            acc = float((predict(xt[:64], p) == yt[:64]).mean())
        if acc >= best_acc:
            best_acc = acc
            best = snapshot(p)
        print(
            f"[{spec_id}] ft {ep}/{epochs} loss={loss_acc / max(steps, 1):.3f} "
            f"web_val={acc:.3f} n_web={len(web_x)} best={best_acc:.3f}",
            flush=True,
        )
        hist.append(acc)
        lr *= 0.9
    p = best
    save_bin(bin_path, p)
    return {"species": spec_id, "n_web_train": int(len(web_x)), "n_web_val": int(len(val_x)), "best_web_val": best_acc, "hist": hist}


def main() -> int:
    split = json.loads(SPLIT.read_text(encoding="utf-8"))
    train_files = set(split["train"])
    results = []
    for sid in SPECIES:
        results.append(finetune_one(sid, train_files, epochs=8, lr=0.01))
    METRICS.write_text(json.dumps({"results": results}, indent=2), encoding="utf-8")
    print(json.dumps({"ok": True, "metrics": str(METRICS)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
