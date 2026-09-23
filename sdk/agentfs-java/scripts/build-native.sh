#!/usr/bin/env bash
set -euo pipefail

module_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$module_dir/../.." && pwd)
case "$(uname -s):$(uname -m)" in
  Linux:aarch64) resource_arch=aarch64 ;;
  Linux:x86_64) resource_arch=amd64 ;;
  *) echo 'Build the native SDK in a Linux arm64 or amd64 environment.' >&2; exit 1 ;;
esac
native_dir="$module_dir/target/native-resources/native/linux-$resource_arch"
mkdir -p "$native_dir" "$module_dir/target/native-build"
cd "$repo_dir"
go build -tags nogspt -buildmode=c-shared -o "$module_dir/target/native-build/libjfs.so" ./sdk/java/libjfs
gzip -n -c "$module_dir/target/native-build/libjfs.so" > "$native_dir/libjfs.so.gz"
