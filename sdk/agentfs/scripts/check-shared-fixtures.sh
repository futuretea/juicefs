#!/usr/bin/env bash
# The edit/apply_patch and search contract cases live only in sdk/agentfs/fixtures;
# every SDK test suite reads that copy. Fail if a forked copy reappears.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
failed=0
for basename in edit_cases.json search_cases.json; do
  canonical="$root/sdk/agentfs/fixtures/$basename"
  copies=$(find "$root/sdk" -name "$basename" -not -path "*/target/*" | grep -vx "$canonical" || true)
  if [[ -n "$copies" ]]; then
    echo "$basename must live only in sdk/agentfs/fixtures; found copies:" >&2
    echo "$copies" >&2
    failed=1
  else
    echo "$basename single source: $canonical"
  fi
done
exit "$failed"
