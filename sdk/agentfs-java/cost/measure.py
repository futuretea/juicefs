#!/usr/bin/env python3
"""Collect five alternating, same-container JVM startup/read and held-process RSS samples."""

import gzip
import json
import statistics
import subprocess
import sys
import time
import zipfile
from pathlib import Path


def parse_rss(output):
    value = output.strip()
    if not value.isascii() or not value.isdecimal() or int(value) <= 0:
        raise ValueError("Expected one positive RSS sample in KiB")
    return int(value)


def verify_ready(line):
    if line != "READY 4096\n":
        raise ValueError("JVM did not report the verified 4096-byte read")


def sample(work, path, output, kind, round_number):
    command = ["java", "-Xms32m", "-Xmx512m", "-cp",
               f"{work}/classes:{work}/{kind}-jars/*", kind.capitalize() + "Read", path]
    with (output / f"{kind}-{round_number}.stderr").open("w") as errors:
        start = time.monotonic_ns()
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=errors, text=True)
        try:
            verify_ready(process.stdout.readline())
            elapsed_ms = (time.monotonic_ns() - start) / 1_000_000
            rss_kib = parse_rss(subprocess.check_output(
                ["ps", "-o", "rss=", "-p", str(process.pid)], text=True))
            process.communicate("\n", timeout=60)
            if process.returncode != 0:
                raise RuntimeError(f"{kind} JVM failed during close: {process.returncode}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdin.close()
            process.stdout.close()
    return {"kind": kind, "round": round_number, "elapsed_ms": elapsed_ms, "rss_kib": rss_kib}


def artifact_sizes(work, kind):
    jars = sorted((work / f"{kind}-jars").glob("*.jar"))
    sdk = next(jar for jar in jars if jar.name.startswith(
        "agentfs-java-" if kind == "new" else "juicefs-hadoop-"))
    native_bytes = 0
    native_gzip_bytes = 0
    with zipfile.ZipFile(sdk) as archive:
        for entry in archive.infolist():
            if Path(entry.filename).name.startswith("libjfs") and entry.filename.endswith(".gz"):
                native_gzip_bytes += entry.file_size
                with archive.open(entry) as packed, gzip.GzipFile(fileobj=packed) as native:
                    while block := native.read(1024 * 1024):
                        native_bytes += len(block)
    return {"runtime_jars": len(jars), "runtime_bytes": sum(jar.stat().st_size for jar in jars),
            "sdk_jar_bytes": sdk.stat().st_size, "native_gzip_bytes": native_gzip_bytes,
            "native_bytes": native_bytes, "jar_names": [jar.name for jar in jars]}


def main():
    work, path, output = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    samples = []
    for round_number in range(1, 6):
        for kind in ("old", "new"):
            samples.append(sample(work, path, output, kind, round_number))
            (output / "samples.json").write_text(json.dumps(samples, indent=2) + "\n")
    summary = {}
    for kind in ("old", "new"):
        summary[kind] = artifact_sizes(work, kind)
        for field in ("elapsed_ms", "rss_kib"):
            values = [row[field] for row in samples if row["kind"] == kind]
            summary[kind][field] = {"min": min(values), "median": statistics.median(values), "max": max(values)}
    report = {"samples": samples, "summary": summary, "synthetic_path": path,
              "config": {"cache_dir": "memory", "cache_mib": 100, "buffer_mib": 300,
                         "no_usage_report": True, "jvm_flags": ["-Xms32m", "-Xmx512m"],
                         "identity": "hdfs", "groups": ["supergroup"], "caller": 0},
              "limits": ["Five local samples; not a throughput benchmark or statistical speedup claim.",
                         "Shared storage/host caches are uncontrolled; process-native caches start fresh.",
                         "Old loader can reuse its extracted /tmp library across these JVMs; new loader extracts each time.",
                         "RSS is one observation after first read while JVM is held open, not peak RSS.",
                         "RSS includes Java, Go and JNR in the consumer process; services are excluded.",
                         "Old runtime uses the published-style shaded SDK plus Hadoop common dependencies.",
                         "New runtime includes the SDK and all declared consumer dependencies."]}
    (output / "cost.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
