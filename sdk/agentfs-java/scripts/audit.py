#!/usr/bin/env python3
"""Check the standalone consumer's runtime JARs and fixed smoke-test report."""

import argparse
import gzip
import json
import re
import sys
import zipfile
from pathlib import Path


FORBIDDEN = re.compile(
    rb"org[/.]apache[/.](?:hadoop|hdfs|yarn|spark|flink|ranger)(?:[/.$;]|$)"
)
OPERATIONS = {"readFile", "writeFile", "listDirectory", "search", "editFile", "applyPatch"}


def _native_size(archive, entry):
    name = Path(entry.filename).name
    raw_name = name[:-3] if name.endswith(".gz") else name
    if not raw_name.startswith("libjfs") or not raw_name.endswith((".so", ".dylib", ".dll")):
        return 0
    if not entry.filename.endswith(".gz"):
        return entry.file_size
    total = 0
    with archive.open(entry) as compressed, gzip.GzipFile(fileobj=compressed) as native:
        while block := native.read(1024 * 1024):
            total += len(block)
    return total


def audit_classpath(directory):
    """Inspect every JAR in the consumer's flat runtime dependency directory."""
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError("Runtime dependency directory does not exist")
    result = {"jars": 0, "classes": 0, "native_bytes": 0}
    try:
        for jar in sorted(directory.glob("*.jar")):
            with zipfile.ZipFile(jar) as archive:
                result["jars"] += 1
                for entry in archive.infolist():
                    if entry.is_dir():
                        continue
                    if FORBIDDEN.search(entry.filename.encode("utf-8")):
                        raise ValueError(f"Forbidden namespace in {jar.name}: {entry.filename}")
                    if entry.filename.lower().endswith(".jar"):
                        raise ValueError(f"Nested JAR in {jar.name}: {entry.filename}")
                    if entry.filename.endswith(".class"):
                        result["classes"] += 1
                        if FORBIDDEN.search(archive.read(entry)):
                            raise ValueError(f"Forbidden class reference in {jar.name}: {entry.filename}")
                    result["native_bytes"] += _native_size(archive, entry)
    except (OSError, EOFError, zipfile.BadZipFile) as error:
        raise ValueError("Could not inspect runtime JARs") from error
    return result


def validate_report(report):
    """Require all six operations and the fixed independently expected results."""
    if not isinstance(report, dict):
        raise ValueError("Consumer report must be an object")
    operations = report.get("operations")
    if (not isinstance(operations, list) or len(operations) != len(OPERATIONS)
            or any(not isinstance(item, str) for item in operations)
            or set(operations) != OPERATIONS):
        raise ValueError("Consumer report must contain exactly the six required operations")
    offsets = report.get("search_offsets")
    if (not isinstance(offsets, list) or any(type(offset) is not int for offset in offsets)
            or offsets != [0, 1, 2]):
        raise ValueError("Consumer search offsets must be exactly [0, 1, 2]")
    if report.get("edited_text") != "cat\n":
        raise ValueError("Consumer edited text must preserve the context newline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("classpath", "report"))
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "classpath":
            result = audit_classpath(args.path)
        else:
            with args.path.open(encoding="utf-8") as source:
                report = json.load(source)
            validate_report(report)
            result = {"valid": True}
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
