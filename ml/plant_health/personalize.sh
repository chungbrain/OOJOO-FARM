#!/usr/bin/env bash
# OOJOO FARM — 모델 개인화 원커맨드 (W8).
#
# 사용:
#   ml/plant_health/personalize.sh --species basil
#   ml/plant_health/personalize.sh --species basil --db /path/to/oojoo.db [--epochs N] [--lr F] [--max-per-class N]
#
# 흐름:
#   1) export_diagnosis.py   — 백엔드 DB 에서 사람이 확정한 진단 프레임을 데이터셋으로 추출
#   2) finetune_diagnosis.py — 온디바이스 모델 파인튜닝 (합성 리플레이 + round-trip 검증)
#   3) verify                — 갱신된 .bin 재로드·추론 1회 + metrics 확인
#
# DB 에 "사람이 확정한" 진단 프레임이 없으면 거절(exit 2) —
# 데이터 없는 파인튜닝은 합성 재학습과 동일해서 개인화가 되기 때문이다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/personalize.sh"
cd "$SCRIPT_DIR"

SPECIES=""
DB="$SCRIPT_DIR/../../backend/data/oojoo.db"
EXTRA=()

usage() { sed -n '2,14p' "$SELF" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --species) SPECIES="$2"; shift 2 ;;
    --species=*) SPECIES="${1#*=}"; shift ;;
    --db) DB="$2"; shift 2 ;;
    --db=*) DB="${1#*=}"; shift ;;
    --epochs) EXTRA+=("--epochs" "$2"); shift 2 ;;
    --epochs=*) EXTRA+=("--epochs" "${1#*=}"); shift ;;
    --lr) EXTRA+=("--lr" "$2"); shift 2 ;;
    --lr=*) EXTRA+=("--lr" "${1#*=}"); shift ;;
    --max-per-class) EXTRA+=("--max-per-class" "$2"); shift 2 ;;
    --max-per-class=*) EXTRA+=("--max-per-class" "${1#*=}"); shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 64 ;;
  esac
done

if [[ -z "$SPECIES" ]]; then
  echo "error: --species required (cherry_tomato|basil|cactus|herb|strawberry|pepper|pumpkin|zucchini)" >&2
  exit 64
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 not found" >&2
  exit 1
fi

echo "=== [1/3] export — DB → 사람 확인 진단 프레임 ($DB) ==="
SUMMARY_JSON="$(python3 export_diagnosis.py --species "$SPECIES" --db "$DB")"
echo "$SUMMARY_JSON"

# export_diagnosis.py 는 JSON 을 stdout 만으로 출력한다 (경고 등 나머지 출력은 stderr).
EXPORTED="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["exported"])' "$SUMMARY_JSON")"
if [[ "$EXPORTED" -lt 1 ]]; then
  {
    echo "error: $SPECIES 의 사람이 확정한 진단 프레임이 $DB 에 없습니다."
    echo "       개인화에는 진단 세션 1건 이상(진단 → 사용자 라벨 확인)이 필요합니다."
    echo "       데이터 없는 파인튜닝은 거절합니다 (합성만 학습 = 재학습과 동일)."
  } >&2
  exit 2
fi

echo "=== [2/3] finetune — 온디바이스 모델 (${SPECIES}.bin) ==="
python3 finetune_diagnosis.py --species "$SPECIES" ${EXTRA[@]+"${EXTRA[@]}"}

echo "=== [3/3] verify — 갱신된 모델 round-trip ==="
MODEL="$SCRIPT_DIR/../../android/slave/app/src/main/assets/models/$SPECIES.bin"
if [[ ! -f "$MODEL" ]]; then
  echo "error: 파인튜닝 후 모델 파일이 없습니다: $MODEL" >&2
  exit 3
fi
python3 - "$MODEL" <<'EOF'
import sys
from pathlib import Path
sys.path.insert(0, ".")
from eval_internet import load_ojp1, predict_one
from species import SPECIES
from train import RNG, paint_plant

mp = Path(sys.argv[1])
p = load_ojp1(mp)
probe = paint_plant(SPECIES[mp.stem], "healthy", RNG)
predict_one(probe[None], p)
print(f"round-trip OK: {mp.name} ({mp.stat().st_size} bytes)")
EOF

echo "done — $SPECIES 모델 갱신 완료. 기기에 반영하려면 slave APK 를 다시 빌드하세요."
