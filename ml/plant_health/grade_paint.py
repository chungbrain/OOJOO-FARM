#!/usr/bin/env python3
"""등급(5단계) x 해충(4단계) 합성 식물 장면 생성기 + Jev급 CNN 학습 공용 모듈.

- train_health_grade.py: 건강등급 5분류 (해충 증상은 배경 노이즈로만 등장 → 독립성 확보)
- train_pest.py:          해충발생 4분류 (건강등급은 혼합 배경으로 등장 → 등급과 무관하게 학습)
- PC 시뮬레이션 simulation/src/vision/plantScene.js가 같은 분포를 JS로 재현한다.

OJP1 모델 포맷/학습 루프는 기존 train.py (종별 6클래스) 와 동일한 계열이며,
dense 출력 수(nclass)만 파라미터화 했다.
"""
from __future__ import annotations

import base64
import json
import math
import struct
from pathlib import Path

import numpy as np

from species import SPECIES
from train import (
    conv_backward,
    conv_forward,
    pool_backward,
    pool_forward,
    snapshot,
    softmax,
    augment,
)

SIZE = 48

# F1 — JevHealthNet 5단계 건강등급
GRADE_LABELS = ["very_healthy", "healthy", "fair", "poor", "very_poor"]
GRADE_KO = {
    "very_healthy": "매우건강",
    "healthy": "건강",
    "fair": "보통",
    "poor": "나쁨",
    "very_poor": "매우나쁨",
}

# F2 — PestNet 4단계 해충발생 (건강진단과 별도 모델)
PEST_LABELS = ["none", "light", "moderate", "severe"]
PEST_KO = {
    "none": "해충없음",
    "light": "경미발생",
    "moderate": "보통발생",
    "severe": "심각발생",
}

# 등급별 스타일 — 잎 수/크기, 색 tint, 시듦, 가장자리 화상, 괴사 반점.
# healthy↔very_healthy 혼동 방지를 위해 tint 간극을 충분히 벌린다 (GAP 특성 기준).
GRADE_STYLE = {
    "very_healthy": dict(leaf_mul=1.70, leaf_scale=1.10, tint=(0.85, 1.15, 0.82), wilt=0, burn=0.00, necrosis=0.00),
    "healthy":      dict(leaf_mul=1.00, leaf_scale=1.00, tint=(0.95, 1.05, 0.88), wilt=0, burn=0.00, necrosis=0.00),
    "fair":         dict(leaf_mul=0.75, leaf_scale=0.95, tint=(1.10, 0.92, 0.45), wilt=3, burn=0.00, necrosis=0.00),
    "poor":         dict(leaf_mul=0.45, leaf_scale=0.90, tint=(1.20, 0.68, 0.30), wilt=8, burn=0.30, necrosis=0.05),
    "very_poor":    dict(leaf_mul=0.25, leaf_scale=0.85, tint=(1.35, 0.45, 0.25), wilt=12, burn=0.50, necrosis=0.16),
}

# 해충 단계별 스타일 — 식해 구멍 수/반경, 검은 벌레 덩어리.
# 단계 간 겹침 최소화 (light↔moderate 혼동 방지).
PEST_STYLE = {
    "none":     dict(holes=(0, 0),   radius=(1.5, 1.5), bugs=0),
    "light":    dict(holes=(4, 8),   radius=(1.5, 2.1), bugs=0),
    "moderate": dict(holes=(14, 22), radius=(2.0, 3.0), bugs=1),
    "severe":   dict(holes=(28, 44), radius=(3.0, 4.5), bugs=3),
}

# 해충 증거 색 — '거의 검정(중성)' 로 통일. 등급 증상(괴사/화상의 밝은 갈색·주황)과
# 색 공간을 완전히 분리해 PestNet 이 등급 신호를 해충으로 오인하지 못하게 한다.
PEST_HOLE_COLORS = [
    np.array([16, 13, 11], np.float32),
    np.array([10, 9, 8], np.float32),
]
PEST_BUG_COLOR = np.array([12, 10, 8], np.float32)

LEAF_COUNT = {"column": 1, "needle": 14, "leaflet": 7, "round": 6, "serrated": 5, "long": 6, "lobe": 4}


def _disk(h, w, cy, cx, ry, rx):
    ys, xs = np.ogrid[:h, :w]
    return ((ys - cy) / max(ry, 1.0)) ** 2 + ((xs - cx) / max(rx, 1.0)) ** 2 <= 1.0


def _random_bg(spec: dict, rng: np.random.Generator):
    """Random pot / wall / soil — 화분을 기억하지 못하게 한다 (train.py 와 동일 계열)."""
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


def paint(spec: dict, width: int, height: int, grade: str, pest_level: str, rng: np.random.Generator) -> np.ndarray:
    """합성 식물 장면. grade(건강등급)와 pest_level(해충)은 독립 파라미터.

    width×height 임의 비율 지원 — 기하 스케일 s 는 폭 기준(48 프레임).
    simulation/src/vision/plantScene.js renderScene 과 동일 기하 규격.
    반환: (height, width, 3) float32, 0..1
    """
    st = GRADE_STYLE[grade]
    ps = PEST_STYLE[pest_level]
    s = width / 48.0  # 기하 파라미터는 48 프레임 기준 → 해상도 스케일
    img = np.zeros((height, width, 3), np.float32)
    sky, soil_c = _random_bg(spec, rng)
    horizon = int(height * rng.uniform(0.58, 0.82))
    img[:horizon] = sky
    img[horizon:] = soil_c
    img[horizon:] += rng.normal(0, 6, img[horizon:].shape)

    leaf = np.array(spec["leaf"], np.float32)
    vein = np.array(spec["vein"], np.float32)
    fruit = np.array(spec["fruit"], np.float32)
    shape = spec["shape"]
    tint_mul = np.array(st["tint"], np.float32)

    if shape == "column":
        n_leaves = 1
    else:
        n_leaves = max(1, int(round(LEAF_COUNT[shape] * st["leaf_mul"] * rng.uniform(0.90, 1.10))))

    leaf_mask = np.zeros((height, width), bool)
    for _ in range(n_leaves):
        if shape == "column":
            # 선인장 계열 — 잎 수 대신 기둥 굵기로 활력 표현
            col_mul = 0.55 + 0.45 * min(st["leaf_mul"], 1.7) / 1.7
            cx, cy = width * 0.5, height * 0.42 + st["wilt"] * 0.3 * s
            ry, rx = height * 0.32 * col_mul * st["leaf_scale"], width * 0.14 * col_mul
        elif shape == "needle":
            cx = rng.uniform(0.2, 0.8) * width
            cy = rng.uniform(0.25, 0.6) * height + st["wilt"] * s
            ry, rx = rng.uniform(6, 11) * st["leaf_scale"] * s, rng.uniform(1.6, 2.8) * s
        else:
            cx = rng.uniform(0.22, 0.78) * width
            cy = rng.uniform(0.18, 0.58) * height + st["wilt"] * s
            base = 7 if shape == "leaflet" else 9
            ry = rng.uniform(base, base + 6) * st["leaf_scale"] * s
            rx = rng.uniform(base - 2, base + 5) * s
            if shape == "long":
                ry, rx = rng.uniform(10, 16) * st["leaf_scale"] * s, rng.uniform(3, 5) * s
            if shape == "lobe":
                ry, rx = rng.uniform(10, 16) * st["leaf_scale"] * s, rng.uniform(10, 16) * s
        mask = _disk(height, width, cy, cx, ry, rx)
        t = leaf * tint_mul + rng.normal(0, 7, 3)
        img[mask] = np.clip(t, 0, 255)
        leaf_mask |= mask
        rr = np.linspace(cy - ry * 0.6, cy + ry * 0.6, 8).astype(int)
        for y in rr:
            x = int(cx)
            if 1 <= y < height - 1 and 1 <= x < width - 1:
                img[y - 1:y + 2, x] = vein

    # 열매 — 활력 있는 등급에서만
    if shape in ("leaflet", "serrated", "long") and grade in ("very_healthy", "healthy", "fair") and rng.random() < 0.45:
        for _ in range(int(rng.integers(1, 4))):
            fy, fx = rng.uniform(0.35, 0.62) * height, rng.uniform(0.3, 0.7) * width
            fr = rng.uniform(2.2, 4.2) * s
            img[_disk(height, width, fy, fx, fr, fr)] = fruit

    # 가장자리 화상 (burn) — 잎 외곽 비율만큼 '밝은 주황갈색'화 (해충 증거색과 분리)
    if st["burn"] > 0 and leaf_mask.any():
        ys, xs = np.where(leaf_mask)
        cy_, cx_ = ys.mean(), xs.mean()
        dist = np.sqrt((ys - cy_) ** 2 + (xs - cx_) ** 2)
        edge = dist > np.quantile(dist, max(0.0, 1.0 - st["burn"]))
        img[ys[edge], xs[edge]] = np.clip(
            img[ys[edge], xs[edge]] * np.array([1.40, 0.50, 0.15], np.float32) + np.array([60, 15, 0], np.float32),
            0, 255,
        )

    # 괴사 반점 (necrosis) — 잎 픽셀 일부를 '밝은 황갈색' 반점화
    # (해충 구멍의 '거의 검정'과 색 공간을 완전히 분리 — PestNet 오인 방지)
    if st["necrosis"] > 0 and leaf_mask.any():
        ys, xs = np.where(leaf_mask)
        k = int(len(ys) * st["necrosis"])
        if k > 0:
            pick = rng.choice(len(ys), size=k, replace=False)
            img[ys[pick], xs[pick]] = np.clip(
                img[ys[pick], xs[pick]] * np.array([1.20, 0.90, 0.35], np.float32) + np.array([55, 30, 0], np.float32),
                0, 255,
            )

    # 해충 — 식해 구멍 + 검은 벌레 덩어리 (등급과 독립적으로 적용)
    if leaf_mask.any():
        lo, hi = ps["holes"]
        ys, xs = np.where(leaf_mask)
        if hi > 0 and len(ys) > 8:
            n = min(int(rng.integers(lo, hi + 1)), len(ys))
            pick = rng.choice(len(ys), size=n, replace=False)
            rlo, rhi = ps["radius"]
            for i in pick:
                py, px = int(ys[i]), int(xs[i])
                r = float(rng.uniform(rlo, rhi)) * s
                hole = _disk(height, width, py, px, r, r) & leaf_mask
                img[hole] = rng.choice(PEST_HOLE_COLORS)
        for _ in range(ps["bugs"]):
            rows = np.where(leaf_mask.any(axis=1))[0]
            if len(rows) == 0:
                break
            by = int(rng.choice(rows))
            cols = np.where(leaf_mask[by])[0]
            bx = int(rng.choice(cols))
            br = rng.uniform(2.5, 4.5) * s
            bug = _disk(height, width, by, bx, br, br * 1.4) & leaf_mask
            img[bug] = PEST_BUG_COLOR

    img = np.clip(img + rng.normal(0, 4.5, img.shape), 0, 255)
    return img.astype(np.float32) / 255.0


def box48(img: np.ndarray) -> np.ndarray:
    """임의 해상도 → 48x48 박스 평균 — simulation cropSample48 과 동일 규칙.
    (셀 경계는 정수 픽셀 범위 합산, 최소 1px 보장)"""
    H, W = img.shape[:2]
    out = np.zeros((48, 48, 3), np.float32)
    for j in range(48):
        y0 = (j * H) // 48
        y1 = max(y0 + 1, ((j + 1) * H) // 48)
        for i in range(48):
            x0 = (i * W) // 48
            x1 = max(x0 + 1, ((i + 1) * W) // 48)
            out[j, i] = img[y0:y1, x0:x1].mean(axis=(0, 1))
    return out


def paint48(spec: dict, grade: str, pest_level: str, rng: np.random.Generator) -> np.ndarray:
    """48x48 학습 샘플 — 다중 스케일·경로.

    학습/시뮬레이터 분포 정합이 핵심: 시뮬레이터는 큰 프레임(480x640) 렌더 후
    48 박스 다운샘플이라 구멍 가장자리가 연화되는데, 직접 48 렌더만으로
    학습하면 연화된 작은 구멍(none↔light 경계)을 못 맞힌다.
    따라서 실측 시뮬 경로(480x640→48)와 축소 경로(144x192→48, 96x96→48)를
    학습 분포에 직접 포함한다.
    """
    r = rng.random()
    if r < 0.12:
        # 실측 시뮬 경로 — 3:4 프레임 480x640 → 박스 다운샘플
        return box48(paint(spec, 480, 640, grade, pest_level, rng))
    if r < 0.42:
        # 축소 3:4 경로 — 비율·연화 근사 (144x192 → 48)
        return box48(paint(spec, 144, 192, grade, pest_level, rng))
    if r < 0.62:
        # 정방형 2x 경로 (96x96 → 48)
        return box48(paint(spec, 96, 96, grade, pest_level, rng))
    return paint(spec, SIZE, SIZE, grade, pest_level, rng)


def init_params(rng: np.random.Generator, nclass: int) -> dict:
    def w(*shape):
        fan = float(np.prod(shape[:-1]))
        return (rng.standard_normal(shape) * math.sqrt(2.0 / fan)).astype(np.float32)

    return {
        "c1w": w(3, 3, 3, 8), "c1b": np.zeros(8, np.float32),
        "c2w": w(3, 3, 8, 16), "c2b": np.zeros(16, np.float32),
        "c3w": w(3, 3, 16, 24), "c3b": np.zeros(24, np.float32),
        "dw": w(24, nclass), "db": np.zeros(nclass, np.float32),
    }


def forward_batch(params: dict, x: np.ndarray) -> np.ndarray:
    """추론 전용 forward — (N,48,48,3) → logits (N,nclass)."""
    h1, _ = conv_forward(x, params["c1w"], params["c1b"])
    p1, _ = pool_forward(h1)
    h2, _ = conv_forward(p1, params["c2w"], params["c2b"])
    p2, _ = pool_forward(h2)
    h3, _ = conv_forward(p2, params["c3w"], params["c3b"])
    p3, _ = pool_forward(h3)
    g = p3.mean(axis=(1, 2))
    return g @ params["dw"] + params["db"]


def train_model(x, y, nclass, class_weights, epochs, lr, log_prefix, rng):
    """train.py 학습 루프의 nclass 파라미터화 버전. 최고 val_acc 스냅샷 반환.
    그래디언트는 전역 노름 클리핑(GLOBAL_CLIP_NORM)으로 스케일링 —
    방향을 보존해 float32 오버플로/발산만 방지한다 (레이어별 클립은
    정상적인 업데이트 크기까지 깎아 학습을 정체시키는 문제가 있었다).
    """
    GLOBAL_CLIP_NORM = 3.0
    val_idx, train_idx = [], []
    for c in range(nclass):
        idx = np.where(y == c)[0]
        n_val = max(8, int(len(idx) * 0.18))
        val_idx.extend(idx[:n_val].tolist())
        train_idx.extend(idx[n_val:].tolist())
    xt, yt, xv, yv = x[train_idx], y[train_idx], x[val_idx], y[val_idx]
    p = init_params(rng, nclass)
    best, best_acc = snapshot(p), -1.0
    bs = 16
    for ep in range(1, epochs + 1):
        perm = rng.permutation(len(yt))
        loss_acc, steps = 0.0, 0
        for i in range(0, len(yt), bs):
            xb, yb = xt[perm[i:i + bs]].copy(), yt[perm[i:i + bs]]
            for j in range(len(xb)):
                xb[j] = augment(xb[j], rng)
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
            weights = np.array(class_weights, np.float32)[yb][:, None]
            dlog = weights * (prob - yoh) / len(yb)
            g_dw = g.T @ dlog
            g_db = dlog.sum(0)
            dg = dlog @ p["dw"].T
            dp3 = np.ones_like(p3) * dg[:, None, None, :] / (p3.shape[1] * p3.shape[2])
            dh3 = pool_backward(dp3, s3)
            dp2, g_c3w, g_c3b = conv_backward(dh3, c3)
            dh2 = pool_backward(dp2, s2)
            dp1, g_c2w, g_c2b = conv_backward(dh2, c2)
            dh1 = pool_backward(dp1, s1)
            _, g_c1w, g_c1b = conv_backward(dh1, c1)
            # 전역 노름 클리핑 — 방향 보존 스케일링
            total = float(np.sqrt(sum(float((g_ ** 2).sum()) for g_ in
                                       (g_dw, g_db, g_c3w, g_c3b, g_c2w, g_c2b, g_c1w, g_c1b))))
            scale = lr * (min(1.0, GLOBAL_CLIP_NORM / max(total, 1e-8)))
            p["dw"] -= scale * g_dw
            p["db"] -= scale * g_db
            p["c3w"] -= scale * g_c3w
            p["c3b"] -= scale * g_c3b
            p["c2w"] -= scale * g_c2w
            p["c2b"] -= scale * g_c2b
            p["c1w"] -= scale * g_c1w
            p["c1b"] -= scale * g_c1b
        pred = forward_batch(p, xv).argmax(1)
        acc = float((pred == yv).mean())
        if acc >= best_acc:
            best_acc = acc
            best = snapshot(p)
        print(f"[{log_prefix}] epoch {ep}/{epochs} loss={loss_acc / max(steps, 1):.3f} val_acc={acc:.3f} best={best_acc:.3f}", flush=True)
        lr *= 0.88
    p = best
    pred = forward_batch(p, xv).argmax(1)
    acc = float((pred == yv).mean())
    cm = np.zeros((nclass, nclass), np.int32)
    for a, b in zip(yv, pred):
        cm[int(a), int(b)] += 1
    return {"params": p, "val_acc": acc, "cm": cm.tolist(), "n_train": int(len(yt)), "n_val": int(len(yv))}


def save_bin(path: Path, params: dict, nclass: int, size: int = SIZE) -> None:
    """OJP1 저장 — PlantHealthNet.kt / simulation ojp1.js 가 동일 파서로 읽는다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(b"OJP1")
        f.write(struct.pack("<III", size, nclass, 4))
        for wkey, bkey, kh, kw, cin, cout in (
            ("c1w", "c1b", 3, 3, 3, 8),
            ("c2w", "c2b", 3, 3, 8, 16),
            ("c3w", "c3b", 3, 3, 16, 24),
        ):
            f.write(struct.pack("<BIIII", 1, kh, kw, cin, cout))
            f.write(np.ascontiguousarray(params[wkey], dtype="<f4").tobytes())
            f.write(np.ascontiguousarray(params[bkey], dtype="<f4").tobytes())
        f.write(struct.pack("<BII", 2, 24, nclass))
        f.write(np.ascontiguousarray(params["dw"], dtype="<f4").tobytes())
        f.write(np.ascontiguousarray(params["db"], dtype="<f4").tobytes())


def export_vectors(out_path: Path, params: dict, model_id: str, labels, cases) -> None:
    """JS 런타임 round-trip 검증용 벡터 — 입력(base64 float32 RGB) + 기대 logits."""
    out = {"modelId": model_id, "labels": list(labels), "size": SIZE, "cases": []}
    for i, (spec_id, grade, pest_level) in enumerate(cases):
        rng = np.random.default_rng(1000 + i)  # 고정 시드 — 재현 가능
        img = paint(SPECIES[spec_id], SIZE, SIZE, grade, pest_level, rng)
        logits = forward_batch(params, img[None])[0]
        out["cases"].append({
            "species": spec_id,
            "grade": grade,
            "pest": pest_level,
            "input": base64.b64encode(np.ascontiguousarray(img, dtype="<f4").tobytes()).decode("ascii"),
            "logits": [round(float(v), 4) for v in logits],
        })
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out), encoding="utf-8")
