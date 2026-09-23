#!/usr/bin/env bash
# Run inside a Linux build environment connected to an existing synthetic test volume.
set -euo pipefail
module_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$module_dir/../.." && pwd)
route=${1:-all}
case "$route" in
  all|native|files|search|edit|regression|cost) ;;
  *) echo 'Usage: verify.sh [all|native|files|search|edit|regression|cost]' >&2; exit 2 ;;
esac
: "${AGENTFS_META:?Set the metadata address for an existing isolated test volume}"
: "${AGENTFS_VOLUME:?Set the existing isolated test volume name}"
cd "$repo_dir"
mvn -B -f "$module_dir/pom.xml" clean
bash "$module_dir/scripts/build-native.sh"
mvn -B -f "$module_dir/pom.xml" install
python3 -m unittest discover -s "$module_dir/scripts" -p 'test_*.py'
work_dir=$(mktemp -d "$module_dir/target/verify-XXXXXXXX")
cp -R "$repo_dir/sdk/agentfs-python/agentfs" "$work_dir/agentfs"
cp "$module_dir/target/native-build/libjfs.so" "$work_dir/agentfs/libjfs.so"
export PYTHONPATH="$work_dir"
test_root="/agentfs-standalone-verify-$(date -u +%Y%m%dT%H%M%S)-$$"
export AGENTFS_TEST_ROOT="$test_root-client"
export AGENTFS_DENIED_FILE="$AGENTFS_TEST_ROOT/private/denied.txt"
export AGENTFS_DIRECTORY_PATH="$AGENTFS_TEST_ROOT/empty-directory"
export AGENTFS_FILES_ROOT="$test_root-files"
export AGENTFS_SEARCH_ROOT="$test_root-search"
export AGENTFS_EDIT_ROOT="$test_root-edit"
export AGENTFS_PERMISSION_ROOT="$test_root-permissions"
export AGENTFS_EXCHANGE_ROOT="$test_root-exchange"
printf 'Synthetic test prefix: %s\n' "$test_root"
python3 "$module_dir/scripts/prepare-fixtures.py"
case "$route" in
  all) tests='*Test,*IT' ;;
  native) tests=NativeClientIT,NativeStatBoundaryIT ;;
  files) tests=NativeFilesIT ;;
  search) tests=NativeSearchIT ;;
  edit) tests=NativeEditIT,NativeEditPermissionIT,NativeEditFaultIT ;;
  regression|cost) tests= ;;
esac
if [[ -n "$tests" ]]; then
  mvn -B -f "$module_dir/pom.xml" "-Dtest=$tests" verify
fi
# Resolve the consumer in a fresh Maven repository: no old SDK or workspace resources.
consumer_dir="$work_dir/consumer"
mkdir -p "$consumer_dir"
cp "$module_dir/consumer/pom.xml" "$consumer_dir/pom.xml"
cp -R "$module_dir/consumer/src" "$consumer_dir/src"
mvn -B "-Dmaven.repo.local=$work_dir/m2" install:install-file \
  "-Dfile=$module_dir/target/agentfs-java-0.1-SNAPSHOT.jar" "-DpomFile=$module_dir/pom.xml"
mvn -B "-Dmaven.repo.local=$work_dir/m2" -f "$consumer_dir/pom.xml" compile \
  dependency:copy-dependencies -DincludeScope=runtime dependency:tree | tee "$work_dir/dependencies.log"
python3 "$module_dir/scripts/audit.py" classpath "$consumer_dir/target/dependency"
java_bin=$(command -v java)
consumer_cp="$consumer_dir/target/classes:$consumer_dir/target/dependency/*"
PATH=/nonexistent "$java_bin" -cp "$consumer_cp" NativeConsumer > "$work_dir/consumer-report.json"
python3 "$module_dir/scripts/audit.py" report "$work_dir/consumer-report.json"
if [[ "$route" == all || "$route" == edit ]]; then
  python3 "$module_dir/scripts/cross_language.py" seed
  "$java_bin" -cp "$consumer_cp" CrossLanguageConsumer exchange
  python3 "$module_dir/scripts/cross_language.py" exchange
  "$java_bin" -cp "$consumer_cp" CrossLanguageConsumer verify
fi
if [[ "$route" == all || "$route" == regression ]]; then
  bash "$module_dir/scripts/regression.sh"
fi
if [[ "$route" == all || "$route" == cost ]]; then
  AGENTFS_COST_OUTPUT_DIR="$work_dir/cost" AGENTFS_COST_NEW_JARS="$consumer_dir/target/dependency" \
    bash "$module_dir/cost/container.sh"
fi
printf 'Verification route %s passed. Local evidence directory: %s\n' "$route" "$work_dir"
