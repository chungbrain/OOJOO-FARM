#!/usr/bin/env python3
"""Export human-confirmed diagnosis frames from the backend DB into a personalization dataset.

동작:
1. 백엔드 SQLite 에서 사람이 확정한 진단 세션(diagnosis_sessions.human_label)을 조회
2. 대상 종(--species)으로 해석되는 세션의 프레임 JPEG 를 데이터셋 디렉터리로 복사
3. manifest.json + 세션 단위 train/val split.json 생성
   (같은 세션의 프레임은 시각적으로 동일 식물·동일 순간이라 한쪽 스플릿에만 배치)

이후: finetune_diagnosis.py 로 온디바이스 모델을 개인화 파인튜닝한다.
데이터셋 디렉터리는 파생물이므로 매 실행 시 재생성된다 (DB 가 원본).
"""
from __future__ import annotations

import argparse
import json
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from species import LABELS, SPECIES

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_DB = ROOT / "backend" / "data" / "oojoo.db"

# 종별 별칭 → 모델 ID 매핑 (PlantHealthNet.resolve 와 동일 규칙).
_ALIASES = {}
for _sid, _spec in SPECIES.items():
    for _a in list(_spec["aliases"]) + [_sid]:
        _ALIASES[_a] = _sid
        _ALIASES[_a.lower()] = _sid
        _ALIASES[_a.lower().replace(" ", "")] = _sid


def resolve_species(model_id, *raws):
    """model_id 우선, 그다음 식물 종/이름의 별칭으로 종별 모델 ID 를 해석한다."""
    if model_id and model_id in SPECIES:
        return model_id
    for raw in raws:
        if not raw:
            continue
        key = raw.strip()
        lower = key.lower()
        if key in _ALIASES:
            return _ALIASES[key]
        if lower in _ALIASES:
            return _ALIASES[lower]
        nospace = lower.replace(" ", "")
        if nospace in _ALIASES:
            return _ALIASES[nospace]
    return None


def export(species_id: str, db_path: Path, diagnosis_dir: Path, out_dir: Path) -> dict:
    if not db_path.exists():
        raise SystemExit(f"backend DB not found: {db_path}")
    con = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    try:
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"diagnosis_sessions", "diagnosis_frames", "plants"} <= tables:
            print(f"warning: diagnosis tables not found in {db_path} — nothing to export")
            return {
                "exported": 0, "skipped_missing": 0, "train": 0, "val": 0,
                "sessions": 0, "species": species_id,
            }
        rows = con.execute("""
            SELECT s.id, s.human_label, s.model_id, s.created_at,
                   p.id AS plant_id, p.species AS plant_species, p.name AS plant_name,
                   f.frame_index
            FROM diagnosis_sessions s
            JOIN plants p ON p.id = s.plant_id
            JOIN diagnosis_frames f ON f.session_id = s.id
            WHERE s.human_label IS NOT NULL
            ORDER BY s.created_at, f.frame_index
        """).fetchall()
    finally:
        con.close()

    # 세션 단위로 정리: 확정 라벨이 유효하고 대상 종으로 해석되는 세션만.
    # (해석 규칙은 추론 때와 동일 — model_id 가 있으면 그것을, 없으면 식물 종/이름 별칭)
    sessions = {}
    for (sid, human_label, model_id, created_at, plant_id, plant_species, plant_name, frame_index) in rows:
        if human_label not in LABELS:
            continue
        resolved = resolve_species(model_id, plant_species, plant_name)
        if resolved != species_id:
            continue
        s = sessions.get(sid)
        if s is None:
            s = {
                "label": human_label, "modelId": resolved, "plantId": plant_id,
                "createdAt": created_at, "frames": set(),
            }
            sessions[sid] = s
        s["frames"].add(int(frame_index))

    # 복사 대상 먼저 확정 (존재하는 프레임 파일만) — 디스크에 없는 프레임은 조용히 건너뛴다.
    plan = []
    skipped_missing = 0
    for sid, s in sessions.items():
        for fi in sorted(s["frames"]):
            if (diagnosis_dir / sid / f"frame_{fi}.jpg").exists():
                plan.append((sid, fi))
            else:
                skipped_missing += 1
    if not plan:
        return {
            "exported": 0, "skipped_missing": skipped_missing, "train": 0, "val": 0,
            "sessions": 0, "species": species_id,
        }

    # 데이터셋은 파생물 — 통째로 재생성한다.
    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "images").mkdir(parents=True)

    items = []
    exported_sessions = {}  # sid -> label (프레임이 1장 이상 복사된 세션)
    for sid, fi in plan:
        name = f"{sid}_{fi:02d}.jpg"
        shutil.copyfile(diagnosis_dir / sid / f"frame_{fi}.jpg", out_dir / "images" / name)
        s = sessions[sid]
        items.append({
            "file": name, "session": sid, "frameIndex": fi, "label": s["label"],
            "modelId": s["modelId"], "plantId": s["plantId"], "createdAt": s["createdAt"],
        })
        exported_sessions[sid] = s["label"]

    # 세션 단위 split: 라벨별로 세션이 3개 이상이면 가장 최근 세션 1개를 val 로.
    # (insertion 순서 = created_at 오름차순이므로 마지막이 최신)
    by_label = {}
    for sid, label in exported_sessions.items():
        by_label.setdefault(label, []).append(sid)
    val_sessions = set()
    for label, sids in by_label.items():
        if len(sids) >= 3:
            val_sessions.add(sids[-1])
    train_files = [it["file"] for it in items if it["session"] not in val_sessions]
    val_files = [it["file"] for it in items if it["session"] in val_sessions]

    manifest = {
        "exportedAt": datetime.now(timezone.utc).isoformat(),
        "db": str(db_path),
        "diagnosisDir": str(diagnosis_dir),
        "species": species_id,
        "items": items,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "split.json").write_text(json.dumps({"train": train_files, "val": val_files}, indent=2), encoding="utf-8")

    return {
        "exported": len(items),
        "skipped_missing": skipped_missing,
        "train": len(train_files),
        "val": len(val_files),
        "sessions": len(exported_sessions),
        "species": species_id,
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--species", required=True, choices=list(SPECIES),
                    help="개인화 대상 종 (기본 진단 대상: basil)")
    ap.add_argument("--db", default=str(DEFAULT_DB), help="백엔드 SQLite DB 경로")
    ap.add_argument("--diagnosis-dir", default=None,
                    help="진단 프레임 디렉터리 (기본값: <db 경로>/diagnosis)")
    ap.add_argument("--out-dir", default=str(HERE / "diagnosis_data"),
                    help="데이터셋 출력 디렉터리 (기본값: ml/plant_health/diagnosis_data)")
    args = ap.parse_args(argv)
    db_path = Path(args.db)
    diagnosis_dir = Path(args.diagnosis_dir) if args.diagnosis_dir else db_path.parent / "diagnosis"
    summary = export(args.species, db_path, diagnosis_dir, Path(args.out_dir))
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
