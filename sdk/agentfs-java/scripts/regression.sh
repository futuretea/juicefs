#!/usr/bin/env bash
set -euo pipefail
# The legacy FFI uses the JVM default charset, including in forked test JVMs.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfile.encoding=UTF-8"
module_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$module_dir/../.." && pwd)
build_dir=$(mktemp -d)
cp -R "$repo_dir/sdk/java" "$build_dir/java"
mkdir -p "$build_dir/python"
cp -R "$repo_dir/sdk/python/juicefs/juicefs" "$build_dir/python/juicefs"
cp "$module_dir/target/native-build/libjfs.so" "$build_dir/python/juicefs/libjfs.so"
ln -s "$repo_dir/.git" "$build_dir/.git"
mkdir -p "$build_dir/java/libjfs/target"
case "$(uname -m)" in
  aarch64) native_arch=arm64 ;;
  x86_64) native_arch=amd64 ;;
  *) echo 'Unsupported regression architecture' >&2; exit 1 ;;
esac
gzip -n -c "$module_dir/target/native-build/libjfs.so" > "$build_dir/java/libjfs/target/libjfs-$native_arch.so.gz"
cp "$module_dir/scripts/legacy-core-site.xml" "$build_dir/java/conf/core-site.xml"
cp "$module_dir/consumer/src/legacy/java/LegacyStatBoundaryTest.java" "$build_dir/java/src/test/java/io/juicefs/"
cd "$repo_dir"
go test -tags nogspt ./sdk/java/libjfs -run 'TestUpdateAllCtx|TestGuidMask|TestGenGuid|TestFillStatIdentityRecordBoundary' -count=1
go build -tags nogspt -o "$build_dir/juicefs-test-cli" .
cd "$build_dir/java"
# Only this newly allocated temporary SQLite volume is formatted.
"$build_dir/juicefs-test-cli" format --storage file --bucket "$build_dir/objects" sqlite3://legacy.db agentfs-legacy
mvn -B -Dfile.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 \
  -Dtest=LegacyStatBoundaryTest,RangerPermissionCheckerTest#testRangerCheckerInitFailed test
JUICEFS_RANGER_TEST=1 mvn -B -Dfile.encoding=UTF-8 -Dproject.build.sourceEncoding=UTF-8 \
  '-Dtest=RangerPermissionCheckerTest,!RangerPermissionCheckerTest#testRangerCheckerInitFailed' test
cd "$repo_dir"
PYTHONPATH="$build_dir/python" AGENTFS_LEGACY_META="sqlite3://$build_dir/java/legacy.db" \
  AGENTFS_LEGACY_VOLUME=agentfs-legacy python3 "$module_dir/scripts/legacy_python_smoke.py"
echo 'Scoped legacy regressions passed; full Go package TestPush and go vet remain baseline limitations.'
