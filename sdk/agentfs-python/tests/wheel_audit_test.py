# Copyright 2026 Juicedata, Inc.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "scripts" / "audit_wheel.py"


class WheelAuditTest(unittest.TestCase):
    def make_wheel(self, directory, *, tag="py3-none-linux_aarch64",
                   dependency="six", machine=183):
        wheel = Path(directory) / f"agentfs-0.1-{tag}.whl"
        elf = bytearray(64)
        elf[:4] = b"\x7fELF"
        elf[4] = 2
        elf[5] = 1
        struct.pack_into("<H", elf, 18, machine)
        with ZipFile(wheel, "w") as archive:
            archive.writestr("agentfs/__init__.py", "")
            archive.writestr("agentfs/libjfs.so", elf)
            archive.writestr("agentfs-0.1.dist-info/METADATA",
                             f"Metadata-Version: 2.1\nName: agentfs\n"
                             f"Version: 0.1\nRequires-Dist: {dependency}\n")
            archive.writestr("agentfs-0.1.dist-info/WHEEL",
                             f"Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: {tag}\n")
        return wheel

    def audit(self, wheel):
        return subprocess.run([sys.executable, str(AUDIT), str(wheel)],
                              capture_output=True, text=True, check=False)

    def test_s1_valid_arm64_native_wheel_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.audit(self.make_wheel(tmp))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_s1_pure_wheel_cannot_claim_native_package(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.audit(self.make_wheel(tmp, tag="py3-none-any"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("platform", result.stderr)

    def test_s1_old_juicefs_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.audit(self.make_wheel(tmp, dependency="juicefs"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("dependency", result.stderr)

    def test_s1_wrong_elf_machine_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.audit(self.make_wheel(tmp, machine=62))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("architecture", result.stderr)


if __name__ == "__main__":
    unittest.main()
