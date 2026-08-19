#!/usr/bin/env python3
"""Train a tiny convnet per plant species and write an on-device weight file."""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

from species import LABELS, SPECIES

SIZE = 48
RNG = np.random.default_rng(7)


def _disk(h, w, cy, cx, ry, rx):
    ys, xs = np.ogrid[:h, :w]
    return ((ys - cy) / max(ry, 1)) ** 2 + ((xs - cx) / max(rx, 1)) ** 2 <= 1.0


def _random_bg(spec: dict, rng: np.random.Generator):
    """Random pot / wall / soil so the net cannot memorize one planter."""
    palettes = [
        np.array([138, 176, 214], np.float32),
        np.array([210, 200, 180], np.float32),
        np.array([90, 100, 110], np.float32),
        np.array([40, 48, 58], np.float32),
        np.array([176, 196, 148], np.float32),
    ]
    sky = palettes[int(rng.integers(0, len(palettes)))] + rng.normal(0, 10, 3)
    soils = [
        np.array(spec["soil"], np.float32),
        np.array([62, 48, 36], np.float32),
        np.array([120, 96, 70], np.float32),
        np.array([40, 36, 32], np.float32),
        np.array([150, 140, 128], np.float32),
    ]
    soil = soils[int(rng.integers(0, len(soils)))] + rng.normal(0, 8, 3)
    return np.clip(sky, 0, 255), np.clip(soil, 0, 255)


def paint_plant(spec: dict, label: str, rng: np.random.Generator) -> np.ndarray:
    img = np.zeros((SIZE, SIZE, 3), np.float32)
    sky, soil_c = _random_bg(spec, rng)
    horizon = int(SIZE * rng.uniform(0.58, 0.82))
    img[:horizon] = sky
    img[horizon:] = soil_c
    img[horizon:] += rng.normal(0, 6, img[horizon:].shape)

    leaf = np.array(spec["leaf"], np.float32)
    vein = np.array(spec["vein"], np.float32)
    fruit = np.array(spec["fruit"], np.float32)
    shape = spec["shape"]
    wilt = 12 if label == "water_low" else 0
    stretch = 1.35 if label == "light_low" else 1.0
    n_leaves = {"column": 1, "needle": 14, "leaflet": 7, "round": 6, "serrated": 5, "long": 6, "lobe": 4}.get(shape, 6)
    leaf_mask = np.zeros((SIZE, SIZE), bool)
    for _ in range(n_leaves):
        if shape == "column":
            cx, cy = SIZE * 0.5, SIZE * 0.42 + wilt * 0.3
            ry, rx = SIZE * 0.32 * stretch, SIZE * 0.14
        elif shape == "needle":
            cx = rng.uniform(0.2, 0.8) * SIZE
            cy = rng.uniform(0.25, 0.6) * SIZE + wilt
            ry, rx = rng.uniform(6, 11) * stretch, rng.uniform(1.6, 2.8)
        else:
            cx = rng.uniform(0.22, 0.78) * SIZE
            cy = rng.uniform(0.18, 0.58) * SIZE + wilt
            base = 7 if shape == "leaflet" else 9
            ry = rng.uniform(base, base + 6) * stretch
            rx = rng.uniform(base - 2, base + 5)
            if shape == "long":
                ry, rx = rng.uniform(10, 16) * stretch, rng.uniform(3, 5)
            if shape == "lobe":
                ry, rx = rng.uniform(10, 16) * stretch, rng.uniform(10, 16)
        mask = _disk(SIZE, SIZE, cy, cx, ry, rx)
        tint = leaf + rng.normal(0, 7, 3)
        if label == "water_low":
            tint = tint * np.array([0.95, 0.62, 0.4], np.float32) + np.array([22, 8, 0], np.float32)
        elif label == "water_high":
            tint = tint * np.array([1.25, 1.12, 0.35], np.float32) + np.array([40, 28, 0], np.float32)
        elif label == "light_low":
            tint = tint * np.array([0.45, 0.55, 0.85], np.float32)
        elif label == "heat":
            tint = tint * np.array([1.08, 0.88, 0.55], np.float32)
        elif label == "healthy":
            tint = tint * np.array([0.9, 1.12, 0.85], np.float32)
        img[mask] = np.clip(tint, 0, 255)
        leaf_mask |= mask
        rr = np.linspace(cy - ry * 0.6, cy + ry * 0.6, 8).astype(int)
        for y in rr:
            x = int(cx)
            if 1 <= y < SIZE - 1 and 1 <= x < SIZE - 1:
                img[y - 1:y + 2, x] = vein

    if shape in ("leaflet", "serrated", "long") and rng.random() < 0.45:
        for _ in range(int(rng.integers(1, 4))):
            fy, fx = rng.uniform(0.35, 0.62) * SIZE, rng.uniform(0.3, 0.7) * SIZE
            fr = rng.uniform(2.2, 4.2)
            img[_disk(SIZE, SIZE, fy, fx, fr, fr)] = fruit

    if label == "water_low":
        if leaf_mask.any():
            img[leaf_mask] = np.clip(img[leaf_mask] * np.array([1.05, 0.7, 0.42], np.float32) + 8, 0, 255)
        if horizon < SIZE:
            img[horizon:] = np.clip(img[horizon:] * 0.78 + np.array([18, 8, 0], np.float32), 0, 255)
    elif label == "water_high":
        if leaf_mask.any():
            img[leaf_mask] = np.clip(img[leaf_mask] * np.array([1.2, 1.05, 0.4], np.float32) + np.array([36, 24, 0], np.float32), 0, 255)
        img[horizon:] = np.clip(img[horizon:] * 0.38 + np.array([10, 16, 22], np.float32), 0, 255)
    elif label == "light_low":
        xs = np.linspace(0.28, 1.0, SIZE, dtype=np.float32)
        if rng.random() < 0.5:
            xs = xs[::-1]
        img *= xs[None, :, None]
        img[..., 2] = np.minimum(255, img[..., 2] * 1.12)
    elif label == "pest":
        ys, xs = np.where(leaf_mask)
        if len(ys) > 8:
            pick = rng.choice(len(ys), size=min(len(ys), int(rng.integers(16, 28))), replace=False)
            for i in pick:
                py, px = int(ys[i]), int(xs[i])
                r = int(rng.integers(2, 4))
                hole = _disk(SIZE, SIZE, py, px, r, r) & leaf_mask
                img[hole] = rng.choice(
                    [np.array([18, 12, 10], np.float32), np.array([70, 28, 16], np.float32)]
                )
    elif label == "heat":
        ys, xs = np.where(leaf_mask)
        if len(ys) > 0:
            cy, cx = ys.mean(), xs.mean()
            dist = np.sqrt((ys - cy) ** 2 + (xs - cx) ** 2)
            edge = dist > np.quantile(dist, 0.45)
            img[ys[edge], xs[edge]] = np.clip(
                img[ys[edge], xs[edge]] * np.array([1.35, 0.55, 0.22], np.float32) + np.array([48, 12, 0], np.float32),
                0, 255,
            )
    else:
        if leaf_mask.any():
            img[leaf_mask] = np.clip(img[leaf_mask] * np.array([0.92, 1.1, 0.88], np.float32), 0, 255)

    img = np.clip(img + rng.normal(0, 4.5, img.shape), 0, 255)
    return img.astype(np.float32) / 255.0


def augment(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    out = img.copy()
    if rng.random() < 0.5:
        out = out[:, ::-1]
    out = np.roll(out, int(rng.integers(-3, 4)), 0)
    out = np.roll(out, int(rng.integers(-3, 4)), 1)
    out = np.clip(out * rng.uniform(0.9, 1.1) + rng.normal(0, 0.012), 0.0, 1.0)
    return out.astype(np.float32)


def dataset(spec: dict, per_class: int):
    xs, ys = [], []
    for yi, lab in enumerate(LABELS):
        for _ in range(per_class):
            xs.append(paint_plant(spec, lab, RNG))
            ys.append(yi)
    x = np.stack(xs)
    y = np.array(ys, np.int64)
    idx = RNG.permutation(len(y))
    return x[idx], y[idx]


def conv_forward(x, w, b):
    n, h, wdt, _ = x.shape
    kh, kw, _, cout = w.shape
    pad = kh // 2
    xp = np.pad(x, ((0, 0), (pad, pad), (pad, pad), (0, 0)))
    patches = np.lib.stride_tricks.sliding_window_view(xp, (kh, kw), axis=(1, 2))
    # (N,H,W,C,KH,KW)
    pre = np.einsum("nhwcjk,jkcd->nhwd", patches, w, optimize=True) + b
    out = np.maximum(pre, 0)
    return out, (x, w, pre, patches)


def conv_backward(dout, cache):
    x, w, pre, patches = cache
    dpre = dout * (pre > 0)
    dw = np.einsum("nhwcjk,nhwd->jkcd", patches, dpre, optimize=True)
    db = dpre.sum(axis=(0, 1, 2))
    kh, kw, cin, cout = w.shape
    pad = kh // 2
    dxp = np.zeros((x.shape[0], x.shape[1] + 2 * pad, x.shape[2] + 2 * pad, x.shape[3]), np.float32)
    # scatter conv grad
    for i in range(kh):
        for j in range(kw):
            dxp[:, i:i + x.shape[1], j:j + x.shape[2], :] += np.einsum("nhwd,cd->nhwc", dpre, w[i, j], optimize=True)
    dx = dxp[:, pad:pad + x.shape[1], pad:pad + x.shape[2], :]
    return dx, dw, db


def pool_forward(x):
    n, h, w, c = x.shape
    view = x.reshape(n, h // 2, 2, w // 2, 2, c)
    out = view.max(axis=(2, 4))
    # argmax mask for backward
    flat = view.reshape(n, h // 2, w // 2, 4, c)
    idx = flat.argmax(axis=3)
    return out, (x.shape, idx)


def pool_backward(dout, cache):
    shape, idx = cache
    n, h, w, c = shape
    dx = np.zeros(shape, np.float32)
    # 2x2 windows: 0=(0,0) 1=(0,1) 2=(1,0) 3=(1,1)
    ys = np.array([0, 0, 1, 1])
    xs = np.array([0, 1, 0, 1])
    for k in range(4):
        mask = idx == k
        dx[:, ys[k]::2, xs[k]::2, :] += dout * mask
    return dx


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / np.clip(e.sum(axis=1, keepdims=True), 1e-9, None)


def init_params(rng):
    def w(*shape):
        fan = float(np.prod(shape[:-1]))
        return (rng.standard_normal(shape) * math.sqrt(2.0 / fan)).astype(np.float32)

    return {
        "c1w": w(3, 3, 3, 8), "c1b": np.zeros(8, np.float32),
        "c2w": w(3, 3, 8, 16), "c2b": np.zeros(16, np.float32),
        "c3w": w(3, 3, 16, 24), "c3b": np.zeros(24, np.float32),
        "dw": w(24, 6), "db": np.zeros(6, np.float32),
    }


def snapshot(p):
    return {k: v.copy() for k, v in p.items()}


def train_one(spec_id: str, per_class: int, epochs: int, lr: float) -> dict:
    spec = SPECIES[spec_id]
    x, y = dataset(spec, per_class)
    val_idx = []
    train_idx = []
    for c in range(6):
        idx = np.where(y == c)[0]
        n_val = max(8, int(len(idx) * 0.18))
        val_idx.extend(idx[:n_val].tolist())
        train_idx.extend(idx[n_val:].tolist())
    xt, yt = x[train_idx], y[train_idx]
    xv, yv = x[val_idx], y[val_idx]
    p = init_params(RNG)
    best = snapshot(p)
    best_acc = -1.0
    bs = 16
    for ep in range(1, epochs + 1):
        perm = RNG.permutation(len(yt))
        loss_acc = 0.0
        steps = 0
        for i in range(0, len(yt), bs):
            xb, yb = xt[perm[i:i + bs]].copy(), yt[perm[i:i + bs]]
            for j in range(len(xb)):
                xb[j] = augment(xb[j], RNG)
            h1, c1 = conv_forward(xb, p["c1w"], p["c1b"])
            p1, s1 = pool_forward(h1)
            h2, c2 = conv_forward(p1, p["c2w"], p["c2b"])
            p2, s2 = pool_forward(h2)
            h3, c3 = conv_forward(p2, p["c3w"], p["c3b"])
            p3, s3 = pool_forward(h3)
            g = p3.mean(axis=(1, 2))
            logits = g @ p["dw"] + p["db"]
            prob = softmax(logits)
            loss_acc += float(-np.log(np.clip(prob[np.arange(len(yb)), yb], 1e-7, 1)).mean())
            steps += 1
            yoh = np.zeros_like(prob)
            yoh[np.arange(len(yb)), yb] = 1
            weights = np.array([1.7, 1.2, 1.6, 1.5, 1.2, 1.4], np.float32)[yb][:, None]
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
        pred = predict(xv, p)
        acc = float((pred == yv).mean())
        if acc >= best_acc:
            best_acc = acc
            best = snapshot(p)
        print(f"[{spec_id}] epoch {ep}/{epochs} loss={loss_acc / max(steps, 1):.3f} val_acc={acc:.3f} best={best_acc:.3f}", flush=True)
        lr *= 0.88
    p = best
    pred = predict(xv, p)
    acc = float((pred == yv).mean())
    cm = np.zeros((6, 6), np.int32)
    for a, b in zip(yv, pred):
        cm[int(a), int(b)] += 1
    return {"params": p, "val_acc": acc, "cm": cm.tolist(), "n_train": int(len(yt)), "n_val": int(len(yv))}


def predict(x, p):
    h1, _ = conv_forward(x, p["c1w"], p["c1b"])
    p1, _ = pool_forward(h1)
    h2, _ = conv_forward(p1, p["c2w"], p["c2b"])
    p2, _ = pool_forward(h2)
    h3, _ = conv_forward(p2, p["c3w"], p["c3b"])
    p3, _ = pool_forward(h3)
    g = p3.mean(axis=(1, 2))
    return softmax(g @ p["dw"] + p["db"]).argmax(1)


def save_bin(path: Path, params: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(b"OJP1")
        f.write(struct.pack("<III", SIZE, 6, 4))
        for wkey, bkey, kh, kw, cin, cout in (
            ("c1w", "c1b", 3, 3, 3, 8),
            ("c2w", "c2b", 3, 3, 8, 16),
            ("c3w", "c3b", 3, 3, 16, 24),
        ):
            f.write(struct.pack("<BIIII", 1, kh, kw, cin, cout))
            f.write(np.ascontiguousarray(params[wkey], dtype="<f4").tobytes())
            f.write(np.ascontiguousarray(params[bkey], dtype="<f4").tobytes())
        f.write(struct.pack("<BII", 2, 24, 6))
        f.write(np.ascontiguousarray(params["dw"], dtype="<f4").tobytes())
        f.write(np.ascontiguousarray(params["db"], dtype="<f4").tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--species", required=True, choices=list(SPECIES))
    ap.add_argument("--per-class", type=int, default=100)
    ap.add_argument("--epochs", type=int, default=14)
    ap.add_argument("--lr", type=float, default=0.03)
    args = ap.parse_args()
    root = Path(__file__).resolve().parents[2]
    out_dir = root / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
    metrics_dir = root / "ml" / "plant_health" / "metrics"
    metrics_dir.mkdir(parents=True, exist_ok=True)
    result = train_one(args.species, args.per_class, args.epochs, args.lr)
    save_bin(out_dir / f"{args.species}.bin", result["params"])
    (out_dir / "labels.json").write_text(json.dumps(LABELS, indent=2), encoding="utf-8")
    meta = {
        "species": args.species,
        "korean": SPECIES[args.species]["ko"],
        "labels": LABELS,
        "val_acc": result["val_acc"],
        "confusion": result["cm"],
        "n_train": result["n_train"],
        "n_val": result["n_val"],
        "input": SIZE,
        "arch": "conv8-16-24 + GAP + dense6",
        "train_recipe": "pot-invariant synthetic + geometric symptoms + augment",
    }
    (metrics_dir / f"{args.species}.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps({"species": args.species, "val_acc": result["val_acc"], "out": str(out_dir / f"{args.species}.bin")}))


if __name__ == "__main__":
    sys.exit(main())
