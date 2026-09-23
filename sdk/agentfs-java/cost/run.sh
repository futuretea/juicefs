#!/usr/bin/env bash
set -euo pipefail
cost_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$cost_dir/../../.." && pwd)
output_dir=$(mktemp -d "$cost_dir/results-XXXXXXXX")
: "${AGENTFS_META:?Set the metadata URL in trusted application configuration}"
: "${AGENTFS_VOLUME:?Set the existing task volume name}"
docker run --rm --network "${AGENTFS_NETWORK:-agentfs-edit-20260918033900-70939-network}" \
  -v "$repo_dir:/workspace:ro" -v "$output_dir:/results" \
  -v agentfs-goal-maven-cache:/root/.m2 \
  -e AGENTFS_META -e AGENTFS_VOLUME -e AGENTFS_COST_OUTPUT_DIR=/results \
  agentfs-goal-sdk-runner bash /workspace/sdk/agentfs-java/cost/container.sh
echo "Cost evidence: $output_dir"
