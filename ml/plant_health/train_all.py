#!/usr/bin/env python3
"""Train every species model with the pot-invariant recipe."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from species import SPECIES

HERE = Path(__file__).resolve().parent


def main() -> int:
    for sid in SPECIES:
        cmd = [sys.executable, str(HERE / "train.py"), "--species", sid]
        print(" ".join(cmd), flush=True)
        rc = subprocess.call(cmd, cwd=str(HERE))
        if rc != 0:
            return rc
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
