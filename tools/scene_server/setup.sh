#!/usr/bin/env bash
# Create the scene server's virtualenv. Needs `uv` (https://docs.astral.sh/uv/).
#
# Python 3.12 and NumPy 1.x are not a preference: the lanelet2 wheel shipped
# with the kitscenes checkout is built for cp38..cp312 against NumPy 1.x and
# aborts on import under NumPy 2.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
kitscenes="${KITSCENES_API_PATH:-$here/../../../kitscenes}"
wheel="$kitscenes/res/ml_converter_wheels/lanelet2-1.2.2-cp312-cp312-manylinux_2_31_x86_64.whl"

[ -f "$wheel" ] || { echo "lanelet2 wheel not found at $wheel" >&2
                     echo "clone https://github.com/KIT-MRT/kitscenes next to this repo," >&2
                     echo "or set KITSCENES_API_PATH to your checkout." >&2; exit 1; }

uv python install 3.12
uv venv --python 3.12 "$here/.venv"
VIRTUAL_ENV="$here/.venv" uv pip install "numpy<2" "pyarrow==17.0.0" "scipy<1.15" pyproj tqdm pillow "$wheel"
echo
echo "done. run it with:"
echo "  KITSCENES_API_PATH=$kitscenes $here/.venv/bin/python -m ll2_scene --scene <scene dir>"
