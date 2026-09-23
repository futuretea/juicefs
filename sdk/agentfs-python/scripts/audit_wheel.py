#!/usr/bin/env python3
"""Check the standalone Linux/arm64 AgentFS wheel before installation."""

import email
import re
import struct
import sys
from pathlib import Path
from zipfile import ZipFile


def _arm64_platform(tag):
    platform = tag.rsplit("-", 1)[-1]
    return platform.endswith("_aarch64") and (
        platform.startswith("linux_") or platform.startswith("manylinux")
    )


def audit(wheel_path):
    wheel_path = Path(wheel_path)
    if not _arm64_platform(wheel_path.stem):
        raise ValueError("wheel filename has no Linux/arm64 platform tag")

    with ZipFile(wheel_path) as archive:
        files = set(archive.namelist())
        if "agentfs/__init__.py" not in files or "agentfs/libjfs.so" not in files:
            raise ValueError("agentfs package or native library is missing")
        if any(name.startswith("juicefs/") for name in files):
            raise ValueError("old juicefs package is bundled")

        metadata_paths = [name for name in files if name.endswith(".dist-info/METADATA")]
        if len(metadata_paths) != 1:
            raise ValueError("expected one distribution metadata file")
        dist_info = metadata_paths[0].rsplit("/", 1)[0]
        metadata = email.message_from_bytes(archive.read(metadata_paths[0]))
        if metadata.get("Name", "").lower() != "agentfs":
            raise ValueError("distribution name must be agentfs")
        for dependency in metadata.get_all("Requires-Dist", []):
            if re.match(r"\s*juicefs(?:\b|\[)", dependency, re.IGNORECASE):
                raise ValueError("old juicefs dependency is forbidden")

        wheel_metadata = email.message_from_bytes(archive.read(dist_info + "/WHEEL"))
        tags = wheel_metadata.get_all("Tag", [])
        if not tags or not all(_arm64_platform(tag) for tag in tags):
            raise ValueError("wheel metadata has no Linux/arm64 platform tag")
        if wheel_metadata.get("Root-Is-Purelib", "").lower() != "false":
            raise ValueError("native wheel cannot claim purelib")

        library = archive.read("agentfs/libjfs.so")
        if len(library) < 20 or library[:6] != b"\x7fELF\x02\x01":
            raise ValueError("native library is not a 64-bit little-endian ELF")
        if struct.unpack_from("<H", library, 18)[0] != 183:
            raise ValueError("native library architecture is not AArch64")


def main(argv):
    if len(argv) != 2:
        print("Usage: audit_wheel.py <wheel>", file=sys.stderr)
        return 2
    try:
        audit(argv[1])
    except (OSError, ValueError) as error:
        print(f"wheel audit failed: {error}", file=sys.stderr)
        return 1
    print("wheel audit passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
