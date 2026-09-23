#!/usr/bin/env bash
set -euo pipefail
work=$(mktemp -d /tmp/agentfs-cost-XXXXXXXX)
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$source_dir/../../.." && pwd)
output_dir=${AGENTFS_COST_OUTPUT_DIR:?Set a directory for local cost evidence}
new_jars=${AGENTFS_COST_NEW_JARS:-$repo_dir/sdk/agentfs-java/consumer/target/dependency}
mkdir -p "$output_dir"
mkdir -p "$work/old/libjfs/target" "$work/new-jars" "$work/old-jars" "$work/classes"
cp -a "$repo_dir/sdk/java/src" "$repo_dir/sdk/java/pom.xml" "$work/old/"
ln -s "$repo_dir/.git" "$work/old/.git"
gzip -n -c "$repo_dir/sdk/agentfs-java/target/native-build/libjfs.so" > "$work/old/libjfs/target/libjfs-arm64.so.gz"
mvn -B -f "$work/old/pom.xml" -DskipTests -Dmaven.javadoc.skip=true package > "$output_dir/old-build.log" 2>&1
mvn -B -f "$source_dir/hadoop-pom.xml" dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="$work/old-jars" > "$output_dir/old-dependencies.log" 2>&1
cp "$work/old/target/juicefs-hadoop-1.5-dev.jar" "$work/old-jars/"
cp "$new_jars/"*.jar "$work/new-jars/"
javac -cp "$work/new-jars/*" -d "$work/classes" "$source_dir/NewRead.java"
javac -cp "$work/old-jars/*" -d "$work/classes" "$source_dir/OldRead.java"
test_path="/agentfs-standalone-cost-$(date -u +%Y%m%dT%H%M%S)-$$/data"
java -Xms32m -Xmx512m -cp "$work/classes:$work/new-jars/*" NewRead "$test_path" seed > "$output_dir/seed.stdout" 2> "$output_dir/seed.stderr"
python3 "$source_dir/measure.py" "$work" "$test_path" "$output_dir"
java -version 2> "$output_dir/java-version.txt"
uname -sm > "$output_dir/platform.txt"
git -C "$repo_dir" rev-parse HEAD > "$output_dir/commit.txt"
