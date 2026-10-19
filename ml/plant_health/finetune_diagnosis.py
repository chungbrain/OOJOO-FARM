#!/usr/bin/env python3
"""Fine-tune an on-device OJP1 model on human-confirmed diagnosis frames (personalization).

사람이 확정한 진단 프레임(export_diagnosis.py 로 추출한 데이터셋)으로
현재 배포된 모델을 파인튜닝해 다시 저장한다 — 모델 개인화 루프의 두 번째 단계.

레시피 (finetune_web.py 패턴 준수):
- 합성 리플레이 16/클래스 (catastrophic forgetting 완화)
- 진단 프레임 최대 max_per_class/클래스 (증강하여 반복 샘플링)
- 배치 16, lr decay x0.9/epoch, val 최고 스냅샷 선택 (val 없으면 학습 배치 추정)
- 종료 후 저장된 .bin round-trip 검증 (읽기 → 추론 1회)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from eval_internet import load_ojp1, load_rgb48, predict_one
from finetune_web import step
from species import LABELS, SPECIES
from train import RNG, SIZE, augment, paint_plant, predict, save_bin, snapshot

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MODEL_DIR = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models"
METRICS_DIR = HERE / "metrics"


def load_images(data_dir: Path, items, files: set):
    """manifest 항목 중 지정 스플릿(train/val) 파일만 48x48 로딩."""
    xs, ys = [], []
    for it in items:
        if it["file"] not in files:
            continue
        path = data_dir / "images" / it["file"]
        if not path.exists():
            continue
        try:
            x = load_rgb48(path)[0]
        except OSError:
            continue
        xs.append(x)
        ys.append(LABELS.index(it["label"]))
    if not xs:
        return np.zeros((0, SIZE, SIZE, 3), np.float32), np.zeros((0,), np.int64)
    return np.stack(xs).astype(np.float32), np.array(ys, np.int64)


def eval_frames(x, y, p):
    """진단 프레임 배치 평가 → (정확도, 혼동행렬). 빈 배치면 (None, None)."""
    if len(x) == 0:
        return None, None
    pred = predict(x, p)
    cm = np.zeros((len(LABELS), len(LABELS)), np.int32)
    for a, b in zip(y, pred):
        cm[int(a), int(b)] += 1
    return float((pred == y).mean()), cm


def build_epoch(spec, diag_x, diag_y, max_per_class):
    """한 에폭 배치: 합성 리플레이 16/클래스 + 진단 프레임 최대 max_per_class/클래스."""
    syn_x, syn_y = [], []
    for yi, lab in enumerate(LABELS):
        for _ in range(16):
            syn_x.append(paint_plant(spec, lab, RNG))
            syn_y.append(yi)
    xs = [augment(im, RNG) for im in syn_x]
    ys = list(syn_y)
    if len(diag_x):
        for c in range(len(LABELS)):
            idx = np.where(diag_y == c)[0]
            if len(idx) == 0:
                continue
            pick = RNG.choice(idx, size=max_per_class, replace=True)
            for i in pick:
                xs.append(augment(diag_x[i], RNG))
                ys.append(int(c))
    x = np.stack(xs).astype(np.float32)
    y = np.array(ys, np.int64)
    perm = RNG.permutation(len(y))
    return x[perm], y[perm]


def finetune(species_id, tr_x, tr_y, vl_x, vl_y, epochs, lr, max_per_class):
    """현재 모델을 로드 → 파인튜닝 → 최고 스냅샷을 .bin 로 저장 → metrics dict."""
    bin_path = MODEL_DIR / f"{species_id}.bin"
    if not bin_path.exists():
        raise SystemExit(f"model not found: {bin_path}")
    spec = SPECIES[species_id]

    p = load_ojp1(bin_path)
    for k in ("_size", "_nclass"):
        p.pop(k, None)

    before_acc, cm_before = eval_frames(vl_x, vl_y, p)

    best = snapshot(p)
    best_acc = -1.0
    best_epoch = 0
    cur = lr
    for ep in range(1, epochs + 1):
        xt, yt = build_epoch(spec, tr_x, tr_y, max_per_class)
        for i in range(0, len(yt), 16):
            step(p, xt[i:i + 16], yt[i:i + 16], cur)
        if len(vl_x):
            acc, _ = eval_frames(vl_x, vl_y, p)
        else:
            acc = float((predict(xt[:64], p) == yt[:64]).mean())
        if acc >= best_acc:
            best_acc = acc
            best = snapshot(p)
            best_epoch = ep
        print(
            f"[{species_id}] ft-diag {ep}/{epochs} val={acc:.3f} n_diag={len(tr_x)} best={best_acc:.3f}",
            flush=True,
        )
        cur *= 0.9
    p = best

    after_acc, cm_after = eval_frames(vl_x, vl_y, p)
    save_bin(bin_path, p)

    # 저장된 .bin round-trip 검증 — 다시 읽어 추론 1회 (포맷 무결성)
    p2 = load_ojp1(bin_path)
    probe = paint_plant(spec, "healthy", RNG)
    predict_one(probe[None], p2)
    roundtrip_ok = True

    per_label = {}
    for lab in LABELS:
        li = LABELS.index(lab)
        per_label[lab] = {"train": int((tr_y == li).sum()), "val": int((vl_y == li).sum())}

    return {
        "species": species_id,
        "n_train": int(len(tr_y)),
        "n_val": int(len(vl_y)),
        "per_label": per_label,
        "epochs": epochs,
        "lr": lr,
        "max_per_class": max_per_class,
        "before_acc": before_acc,
        "after_acc": after_acc,
        "confusion_before": None if cm_before is None else cm_before.tolist(),
        "confusion_after": None if cm_after is None else cm_after.tolist(),
        "best_epoch": best_epoch,
        "roundtrip_ok": roundtrip_ok,
        "val_note": None if len(vl_x) else "val empty — accuracy is train-estimated (noisy)",
    }


def render_md(m):
    ko = SPECIES[m["species"]]["ko"]
    lines = [
        f"# {ko} 모델 진단 라벨 파인튜닝 (모델 개인화)",
        "",
        "사람이 확정한 진단 프레임으로 온디바이스 CNN 을 파인튜닝했습니다.",
        f"- 대상 종: {ko} (`{m['species']}`), 모델: `android/slave/app/src/main/assets/models/{m['species']}.bin`",
        f"- 데이터: 사람 라벨 프레임 {m['n_train'] + m['n_val']}장 (train {m['n_train']} / val {m['n_val']})",
        f"- 레시피: 합성 리플레이 16/클래스 + 진단 프레임 최대 {m['max_per_class']}/클래스, epochs {m['epochs']}, lr {m['lr']}",
        "",
    ]
    if m["before_acc"] is not None:
        lines += [
            "| 지표 | 파인튜닝 전 | 파인튜닝 후 |",
            "|---|---:|---:|",
            f"| val 일치율 | {m['before_acc']:.1%} | **{m['after_acc']:.1%}** |",
            "",
        ]
    else:
        lines += ["- val 프레임 없음 — 학습 배치 추정 정확도만 기록 (노이즈 큼)", ""]
    lines += [
        "| 라벨 | train | val |",
        "|---|---:|---:|",
    ]
    for lab in LABELS:
        c = m["per_label"][lab]
        lines.append(f"| {lab} | {c['train']} | {c['val']} |")
    lines += [
        "",
        "## 해석",
        "",
        "- 사람 라벨은 실사용 환경(실제 화분·카메라·조명)에서 나온 라벨이라 합성 학습 데이터보다 배포 환경에 가깝습니다.",
        "- 합성 리플레이를 섞어 기존 판단(합성 기반)을 잊지 않도록 했습니다 (catastrophic forgetting 완화).",
        "- 세션이 더 쌓이면 export_diagnosis.py → finetune_diagnosis.py 를 다시 실행해 모델을 갱신하세요.",
        "",
    ]
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--species", default="basil", choices=list(SPECIES))
    ap.add_argument("--data-dir", default=str(HERE / "diagnosis_data"),
                    help="export_diagnosis.py 가 생성한 데이터셋 디렉터리")
    ap.add_argument("--epochs", type=int, default=10)
    ap.add_argument("--lr", type=float, default=0.01)
    ap.add_argument("--max-per-class", type=int, default=24)
    ap.add_argument("--tag", default="finetune_diagnosis", help="metrics 출력 파일명 태그")
    args = ap.parse_args(argv)

    data_dir = Path(args.data_dir)
    manifest_path = data_dir / "manifest.json"
    split_path = data_dir / "split.json"
    if not manifest_path.exists() or not split_path.exists():
        print("no confirmed diagnosis frames; run export_diagnosis.py first")
        return 1
    man = json.loads(manifest_path.read_text(encoding="utf-8"))
    split = json.loads(split_path.read_text(encoding="utf-8"))
    train_files = set(split.get("train", []))
    val_files = set(split.get("val", []))
    tr_x, tr_y = load_images(data_dir, man["items"], train_files)
    vl_x, vl_y = load_images(data_dir, man["items"], val_files)
    if len(tr_x) == 0 and len(vl_x) == 0:
        print("no confirmed diagnosis frames; run export_diagnosis.py first")
        return 1

    metrics = finetune(args.species, tr_x, tr_y, vl_x, vl_y, args.epochs, args.lr, args.max_per_class)
    metrics["data_dir"] = str(data_dir)

    out_json = METRICS_DIR / f"{args.tag}.json"
    out_md = METRICS_DIR / f"{args.tag}.md"
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    out_md.write_text(render_md(metrics), encoding="utf-8")

    print(json.dumps({
        "ok": True,
        "species": args.species,
        "before_acc": metrics["before_acc"],
        "after_acc": metrics["after_acc"],
        "n_train": metrics["n_train"],
        "n_val": metrics["n_val"],
        "model": str(MODEL_DIR / f"{args.species}.bin"),
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
