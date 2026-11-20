#!/usr/bin/env python3
"""종별 학습 데이터셋 생성 — on-device 모델(48x48, 6클래스) 학습용.

train.py 의 pot-invariant 합성 페인터(paint_plant)를 재사용해
결정적(시드 고정)으로 이미지를 생성하고 디스크에 영구 저장한다.

레이아웃:
  datasets/<species>/<label>/0000.png ...   (전체, gitignore — 로컬 생성)
  datasets/<species>/sample/<label>_00.png  (샘플, 커밋)
  datasets/<species>/manifest.json          (카운트/시드/다이제스트, 커밋)

같은 시드로는 항상 같은 데이터셋이 재현된다. --verify 로 무결성 확인.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

from species import LABELS, SPECIES
from train import SIZE, paint_plant

HERE = Path(__file__).resolve().parent
DATA_ROOT = HERE / "datasets"


def class_digest(dir_path: Path) -> str:
    h = hashlib.sha256()
    for f in sorted(dir_path.glob("*.png")):
        h.update(hashlib.sha256(f.read_bytes()).hexdigest().encode())
    return h.hexdigest()


def gen_species(spec_id: str, per_class: int, seed: int, samples: int, fresh: bool) -> dict:
    spec = SPECIES[spec_id]
    species_dir = DATA_ROOT / spec_id
    si = list(SPECIES).index(spec_id)
    manifest_path = species_dir / "manifest.json"
    classes: dict = {}

    # 기존 manifest 가 있으면 (비 fresh) 클래스별 시드만 재사용하고 파일은 다시 쓰지 않음
    if fresh or not manifest_path.exists():
        for (li, label) in enumerate(LABELS):
            rng = np.random.default_rng([seed, si, li])
            cdir = species_dir / label
            cdir.mkdir(parents=True, exist_ok=True)
            for old in cdir.glob("*.png"):
                old.unlink()
            for i in range(per_class):
                img = paint_plant(spec, label, rng)  # (48,48,3) 0..1 float32
                arr = (np.clip(img, 0, 1) * 255).round().astype(np.uint8)
                Image.fromarray(arr, "RGB").save(cdir / f"{i:04d}.png")
            classes[label] = {"count": per_class, "digest": class_digest(cdir)}
        manifest = {
            "species": spec_id,
            "korean": spec["ko"],
            "seed": seed,
            "per_class": per_class,
            "size": SIZE,
            "labels": LABELS,
            "generator": "gen_dataset.py",
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "classes": classes,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    else:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        classes = manifest["classes"]

    # 샘플 (커밋 대상): 클래스별 앞부분 N 장
    sdir = species_dir / "sample"
    sdir.mkdir(parents=True, exist_ok=True)
    for old in sdir.glob("*.png"):
        old.unlink()
    for (li, label) in enumerate(LABELS):
        for i in range(min(samples, classes[label]["count"])):
            (sdir / f"{label}_{i:02d}.png").write_bytes(
                (species_dir / label / f"{i:04d}.png").read_bytes()
            )
    return manifest


def verify(spec_id: str) -> int:
    manifest_path = DATA_ROOT / spec_id / "manifest.json"
    if not manifest_path.exists():
        print(f"[verify] {spec_id}: manifest 없음 — 먼저 생성")
        return 1
    m = json.loads(manifest_path.read_text(encoding="utf-8"))
    ok = True
    for label, info in m["classes"].items():
        cdir = DATA_ROOT / spec_id / label
        files = sorted(cdir.glob("*.png")) if cdir.exists() else []
        if len(files) != info["count"]:
            print(f"[verify] {spec_id}/{label}: 파일 {len(files)}/{info['count']}")
            ok = False
            continue
        digest = class_digest(cdir)
        if digest != info["digest"]:
            print(f"[verify] {spec_id}/{label}: 다이제스트 불일치")
            ok = False
    print(f"[verify] {spec_id}: {'OK' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--species", choices=list(SPECIES), help="대상 종 (생략 시 --all)")
    ap.add_argument("--all", action="store_true", help="전체 종")
    ap.add_argument("--per-class", type=int, default=100)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--samples", type=int, default=2, help="커밋할 클래스별 샘플 수")
    ap.add_argument("--verify", action="store_true", help="생성 대신 기존 데이터셋 무결성 확인")
    args = ap.parse_args()

    targets = [s for s in SPECIES if (args.all or args.species == s)]
    if not targets:
        ap.error("--species 또는 --all 필요")

    if args.verify:
        rc = 0
        for s in targets:
            rc |= verify(s)
        return rc

    rc = 0
    for s in targets:
        m = gen_species(s, args.per_class, args.seed, args.samples, fresh=not args.verify)
        n = sum(c["count"] for c in m["classes"].values())
        print(f"[gen] {s} ({m['korean']}): {n} 이미지 ({args.per_class}x{len(LABELS)}) -> {DATA_ROOT / s}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
