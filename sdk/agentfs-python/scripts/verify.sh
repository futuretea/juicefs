#!/usr/bin/env bash
set -euo pipefail

module_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$module_dir/../.." && pwd)
route=${1:-all}
case "$route" in
  build|all) ;;
  *) echo 'Usage: verify.sh [build|all]' >&2; exit 2 ;;
esac

if [[ "$route" == all ]]; then
  work_dir=$(mktemp -d /tmp/agentfs-python-all-XXXXXXXX)
  image=agentfs-python-runner
  docker build --platform linux/arm64 -f "$repo_dir/sdk/agentfs/Dockerfile.runner" \
    -t "$image" "$repo_dir/sdk/agentfs"
  docker run --rm --platform linux/arm64 \
    -v "$repo_dir:/work:ro" -v "$work_dir:/out" -w /work \
    "$image" bash -c 'set -euo pipefail
      PYTHONPATH=sdk/agentfs-python python3 -m unittest discover \
        -s sdk/agentfs-python/tests -p "*_test.py"
      AGENTFS_OUTPUT_DIR=/out/wheels bash sdk/agentfs-python/scripts/verify.sh build
      go build -o /out/juicefs .
      /out/juicefs format --storage file --bucket /out/objects --enable-acl --trash-days 0 \
        sqlite3:///out/volume.db agentfs-python'
  cp "$module_dir/consumer/run.py" "$work_dir/run.py"
  cp "$repo_dir/sdk/agentfs/fixtures/edit_cases.json" "$work_dir/edit_cases.json"
  docker run --rm --platform linux/arm64 \
    -v "$work_dir:/out" -w /out \
    -e AGENTFS_META=sqlite3:///out/volume.db -e AGENTFS_VOLUME=agentfs-python \
    "$image" bash -c 'set -euo pipefail
      python3 -m venv /out/venv
      /out/venv/bin/pip install --no-index /out/wheels/*.whl
      /out/venv/bin/python /out/run.py exercise
      chmod -R a+rwX /out
      runuser -u nobody -- /out/venv/bin/python /out/run.py permissions
      /out/venv/bin/python /out/run.py verify_permissions
      /out/venv/bin/pip list --format=freeze'
  printf 'Standalone Python AgentFS verification passed. Evidence: %s\n' "$work_dir"
  exit 0
fi

case "$(uname -s):$(uname -m)" in
  Linux:aarch64) ;;
  *) echo 'Build the Python AgentFS wheel in Linux/arm64.' >&2; exit 1 ;;
esac

work_dir=$(mktemp -d /tmp/agentfs-python-verify-XXXXXXXX)
mkdir -p "$work_dir/source/agentfs" "$work_dir/dist"
cp -R "$module_dir/agentfs/." "$work_dir/source/agentfs/"
cp "$module_dir/pyproject.toml" "$module_dir/setup.py" "$work_dir/source/"

cd "$repo_dir"
go build -tags nogspt -buildmode=c-shared -o "$work_dir/source/agentfs/libjfs.so" ./sdk/java/libjfs
cd "$work_dir/source"
python3 -m build --wheel --no-isolation --outdir "$work_dir/dist"
wheel=$(find "$work_dir/dist" -maxdepth 1 -name 'agentfs-*.whl' -print -quit)
test -n "$wheel"
python3 "$module_dir/scripts/audit_wheel.py" "$wheel"
if [[ -n "${AGENTFS_OUTPUT_DIR:-}" ]]; then
  mkdir -p "$AGENTFS_OUTPUT_DIR"
  cp "$wheel" "$AGENTFS_OUTPUT_DIR/"
  wheel="$AGENTFS_OUTPUT_DIR/$(basename "$wheel")"
fi
printf 'Wheel: %s\n' "$wheel"
