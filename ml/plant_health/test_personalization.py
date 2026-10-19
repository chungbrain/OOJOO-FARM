#!/usr/bin/env python3
"""E2E: 시드 DB → export_diagnosis → finetune_diagnosis — 바질 개인화 파이프라인 통째로 검증.

시드 DB(실제 백엔드 스키마)와 실제 프레임 JPEG 를 만들어
export → split 검증 → finetune → metrics 검증까지 실경로로 실행한다.
배포된 basil.bin 은 시작 전 백업하고 finally 에서 반드시 복원한다.

실행: python ml/plant_health/test_personalization.py  (전부 통과 시 exit 0)
"""
from __future__ import annotations

import json
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

import export_diagnosis
import finetune_diagnosis
from species import LABELS, SPECIES
from train import RNG, paint_plant

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASIL_BIN = ROOT / "android" / "slave" / "app" / "src" / "main" / "assets" / "models" / "basil.bin"

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print(f"  PASS {name}")
    else:
        FAILED.append((name, str(detail)))
        print(f"  FAIL {name} :: {detail}")


# ---- 시드: 라벨별 basil 세션 수. healthy/water_low 은 3세션 → val 파티션 케이스 포함. ----
LABEL_SESSIONS = {
    "healthy": 3, "water_low": 3, "water_high": 2, "light_low": 2, "pest": 2, "heat": 2,
}
ALIAS_LABEL = "healthy"  # model_id 없이 한글 종 이름("바질")으로만 해석되는 세션
N_FRAMES = 3


def seed(root: Path):
    """실제 백엔드 스키마로 가짜 DB + 진단 프레임 JPEG 를 만든다."""
    root.mkdir(parents=True, exist_ok=True)
    db = root / "seed.db"
    diag = root / "diagnosis"
    con = sqlite3.connect(db)
    con.executescript("""
        CREATE TABLE plants(
          id TEXT PRIMARY KEY, user_id TEXT, slave_id TEXT, name TEXT,
          species TEXT, planted_at TEXT, stage TEXT);
        CREATE TABLE diagnosis_sessions(
          id TEXT PRIMARY KEY, slave_id TEXT NOT NULL, plant_id TEXT NOT NULL,
          video_id TEXT, command_id TEXT, model_id TEXT, ai_label TEXT NOT NULL,
          ai_confidence REAL, human_label TEXT, confirmed_by TEXT, confirmed_at TEXT,
          created_at TEXT DEFAULT (datetime('now')));
        CREATE TABLE diagnosis_frames(
          id TEXT PRIMARY KEY, session_id TEXT NOT NULL, frame_index INTEGER NOT NULL,
          timestamp_ms INTEGER, condition TEXT, confidence REAL, filename TEXT NOT NULL);
    """)
    basil = SPECIES["basil"]
    strawberry = SPECIES["strawberry"]
    con.execute("INSERT INTO plants VALUES(?,?,?,?,?,?,?)",
                ("plantB1", "u1", "s1", "basil pot", "바질", None, "growing"))
    con.execute("INSERT INTO plants VALUES(?,?,?,?,?,?,?)",
                ("plantS1", "u1", "s1", "strawberry pot", "딸기", None, "growing"))

    state = {"t": 0}
    newest_by_label = {}  # 파일이 실제로 존재하는 세션 중 라벨별 최신 sid

    def add_session(sid, plant_id, model_id, label, spec, create_files=True, track=True):
        created = f"2026-11-01 {state['t']:02d}:00:00"
        state["t"] += 1
        con.execute(
            "INSERT INTO diagnosis_sessions(id, slave_id, plant_id, model_id, ai_label, "
            "ai_confidence, human_label, confirmed_by, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (sid, "s1", plant_id, model_id, label, 0.6, label, "user1", created),
        )
        for fi in range(N_FRAMES):
            con.execute(
                "INSERT INTO diagnosis_frames(id, session_id, frame_index, timestamp_ms, "
                "condition, confidence, filename) VALUES(?,?,?,?,?,?,?)",
                (f"{sid}f{fi}", sid, fi, fi * 500, label, 0.6, f"frame_{fi}.jpg"),
            )
            if create_files:
                img = paint_plant(spec, label, RNG)
                arr = (np.clip(img, 0.0, 1.0) * 255.0).astype(np.uint8)
                path = diag / sid / f"frame_{fi}.jpg"
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.fromarray(arr).resize((96, 96), Image.Resampling.BILINEAR).save(
                    path, format="JPEG", quality=88)
        # val 기대치 추적: basil 로 해석되어 export 될 세션만 대상
        # (딸기 세션은 종 필터로 제외되므로 추적하지 않는다)
        if create_files and track:
            newest_by_label[label] = sid

    # 1) basil 정상 세션: 라벨별 LABEL_SESSIONS 개
    for label, n in LABEL_SESSIONS.items():
        for i in range(n):
            add_session(f"basil_{label}_{i:02d}", "plantB1", "basil", label, basil)
    # 2) alias 세션: model_id NULL, 식물 종이 한글 "바질" → 별칭으로 해석되어 포함
    add_session("basil_alias_ko", "plantB1", None, ALIAS_LABEL, basil)
    # 3) 비대상 종(딸기/strawberry) 세션 — 종 필터로 제외되어야 함 (val 추적도 제외)
    add_session("strawberry_excluded", "plantS1", "strawberry", "healthy", strawberry, track=False)
    # 4) 프레임 파일이 디스크에 없는 세션 — 조용히 스킵되어야 함
    add_session("basil_missing_files", "plantB1", "basil", "water_low", basil, create_files=False)

    con.commit()
    con.close()
    return db, diag, newest_by_label


def main() -> int:
    print("== basil personalization pipeline E2E ==")
    backup = BASIL_BIN.read_bytes()
    tmp = Path(tempfile.mkdtemp(prefix="oojoo_pipeline_"))
    test_tag = "finetune_diagnosis_test"
    metrics_json = HERE / "metrics" / f"{test_tag}.json"
    metrics_md = HERE / "metrics" / f"{test_tag}.md"

    expected_items = (sum(LABEL_SESSIONS.values()) + 1) * N_FRAMES  # alias 세션 포함
    expected_val_labels = {lab for lab, n in LABEL_SESSIONS.items() if n >= 3} | {ALIAS_LABEL}

    error = None
    try:
        db, diag, newest = seed(tmp / "seed_root")

        # ---------- 1) export ----------
        out_dir = tmp / "dataset"
        rc = export_diagnosis.main([
            "--species", "basil", "--db", str(db),
            "--diagnosis-dir", str(diag), "--out-dir", str(out_dir),
        ])
        check("export returns 0", rc == 0, f"rc={rc}")
        man = json.loads((out_dir / "manifest.json").read_text(encoding="utf-8"))
        split = json.loads((out_dir / "split.json").read_text(encoding="utf-8"))
        items = man["items"]
        check(f"export item count == {expected_items}", len(items) == expected_items,
              f"got {len(items)}")
        check("alias session (Korean species) included",
              any(it["session"] == "basil_alias_ko" for it in items))
        check("non-basil session excluded",
              not any(it["session"] == "strawberry_excluded" for it in items))
        check("missing-file session skipped",
              not any(it["session"] == "basil_missing_files" for it in items))

        train, val = set(split["train"]), set(split["val"])
        files = {it["file"] for it in items}
        check("split covers all files exactly once",
              train | val == files and not (train & val))

        # 세션 단위 스플릿: 같은 세션의 프레임이 train/val 양쪽에 걸치지 않는다
        sess_files = {}
        for it in items:
            sess_files.setdefault(it["session"], set()).add(it["file"])
        straddle = [s for s, fs in sess_files.items() if fs & train and fs & val]
        check("no session straddles train/val", not straddle, str(straddle))

        # val 은 라벨별 최신 세션 1개 (파일 있는 세션 기준)
        expected_val_sessions = {newest[lab] for lab in expected_val_labels}
        val_sessions = {it["session"] for it in items if it["file"] in val}
        check("val picks newest session per label (>=3 sessions)",
              val_sessions == expected_val_sessions,
              f"{sorted(val_sessions)} != {sorted(expected_val_sessions)}")
        # 세션이 2개뿐인 라벨은 전부 train
        two_labels = {lab for lab, n in LABEL_SESSIONS.items() if n < 3} - {ALIAS_LABEL}
        two_in_val = [it["file"] for it in items if it["label"] in two_labels and it["file"] in val]
        check("labels with <3 sessions stay in train", not two_in_val, str(two_in_val))

        # ---------- 2) finetune (실제 basil.bin 저장 → 검증 후 복원) ----------
        rc2 = finetune_diagnosis.main([
            "--species", "basil", "--data-dir", str(out_dir),
            "--epochs", "3", "--tag", test_tag,
        ])
        check("finetune returns 0", rc2 == 0, f"rc={rc2}")
        m = json.loads(metrics_json.read_text(encoding="utf-8"))
        check("metrics roundtrip_ok", m.get("roundtrip_ok") is True)
        check("metrics counts match split",
              m.get("n_train") == len(train) and m.get("n_val") == len(val),
              f"train={m.get('n_train')}/{len(train)} val={m.get('n_val')}/{len(val)}")
        check("before/after acc recorded",
              m.get("before_acc") is not None and m.get("after_acc") is not None)
        check("basil.bin bytes updated by finetune",
              BASIL_BIN.read_bytes() != backup, "identical to pre-test bytes")
        check("metrics md written", metrics_md.exists())
    except Exception as e:  # 예외가 나도 finally 에서 모델은 복원된다
        error = f"{type(e).__name__}: {e}"
    finally:
        BASIL_BIN.write_bytes(backup)
        shutil.rmtree(tmp, ignore_errors=True)
        metrics_json.unlink(missing_ok=True)
        metrics_md.unlink(missing_ok=True)

    check("basil.bin restored byte-identical", BASIL_BIN.read_bytes() == backup)
    if error:
        FAILED.append(("unexpected exception", error))
        print(f"  FAIL unexpected exception :: {error}")

    print()
    print(f"RESULT: {len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        for name, detail in FAILED:
            print(f"  - {name}: {detail}")
        return 1
    print("PASS: personalization pipeline (export → split → finetune → restore) all green")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
