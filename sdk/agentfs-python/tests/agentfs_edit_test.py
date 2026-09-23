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

"""Synthetic public edit API contract cases; no mounted filesystem required."""

import base64
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from agentfs.agentfs import AgentFS


class EditLocalClient:
    def __init__(self, root):
        self.root = Path(root)
        self.renames = 0

    def _path(self, path):
        return self.root / path.lstrip("/")

    def access(self, path, mode):
        pass

    def open(self, path, mode):
        return self._path(path).open(mode)

    def lstat(self, path):
        return self._path(path).lstat()

    stat = lstat

    def listxattr(self, path):
        return []

    def chmod(self, path, mode):
        os.chmod(self._path(path), mode)

    def chown(self, path, uid, gid):
        os.chown(self._path(path), uid, gid)

    def rename(self, source, target):
        self.renames += 1
        os.replace(self._path(source), self._path(target))

    def unlink(self, path):
        self._path(path).unlink()


class AgentFSEditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = EditLocalClient(self.root)
        self.fs = AgentFS(self.client)

    def test_unique_replacement(self):
        (self.root / "a").write_bytes(b"hello cat")
        result = self.fs.edit_file("/a", "cat", "dog")
        self.assertEqual(b"hello dog", (self.root / "a").read_bytes())
        self.assertEqual({"path": "/a", "replacements": 1, "bytes_written": 9}, result)

    def test_overlapping_match_is_ambiguous_and_unchanged(self):
        (self.root / "a").write_bytes(b"aaa")
        before = (self.root / "a").stat()
        with self.assertRaises(Exception) as caught:
            self.fs.edit_file("/a", "aa", "b")
        self.assertEqual("ambiguous_match", getattr(caught.exception, "code", None))
        self.assertEqual("unchanged", getattr(caught.exception, "outcome", None))
        self.assertEqual(b"aaa", (self.root / "a").read_bytes())
        self.assertEqual(before.st_ino, (self.root / "a").stat().st_ino)
        self.assertEqual(0, self.client.renames)

    def test_shared_fixtures(self):
        fixture = Path(__file__).resolve().parents[2] / "agentfs/fixtures/edit_cases.json"
        for case in json.loads(fixture.read_text())["cases"]:
            with self.subTest(case=case["id"]):
                target = self.root / "a"
                original = base64.b64decode(case["input_b64"])
                target.write_bytes(original)
                before = target.stat()
                self.client.renames = 0
                if case["operation"] == "edit":
                    call = lambda: self.fs.edit_file("/a", case["old_text"], case["new_text"])
                else:
                    call = lambda: self.fs.apply_patch("/a", case["patch"])
                if "error" in case:
                    with self.assertRaises(Exception) as caught:
                        call()
                    self.assertEqual(case["error"]["code"], getattr(caught.exception, "code", None))
                    self.assertEqual("unchanged", getattr(caught.exception, "outcome", None))
                    self.assertEqual(original, target.read_bytes())
                    self.assertEqual(before.st_ino, target.stat().st_ino)
                    self.assertEqual(0, self.client.renames)
                else:
                    expected = base64.b64decode(case["expected_b64"])
                    result = call()
                    self.assertEqual(expected, target.read_bytes())
                    self.assertEqual(case["replacements"], result["replacements"])
                    self.assertEqual(len(expected) if case["replacements"] else 0, result["bytes_written"])
                    self.assertEqual((before.st_uid, before.st_gid, before.st_mode),
                                     (target.stat().st_uid, target.stat().st_gid, target.stat().st_mode))
                    if not case["replacements"]:
                        self.assertEqual(before.st_ino, target.stat().st_ino)
                        self.assertEqual(0, self.client.renames)

    def test_deleting_final_line_keeps_context_newline(self):
        for newline in (b"\n", b"\r\n"):
            for include_context in (False, True):
                with self.subTest(newline=newline, include_context=include_context):
                    target = self.root / "a"
                    target.write_bytes(b"cat" + newline + b"dog")
                    self.client.renames = 0
                    patch = (b"*** Begin Patch\n*** Update File: /a\n@@\n"
                             + (b" cat" + newline if include_context else b"")
                             + b"-dog" + newline + b"*** End of File\n*** End Patch\n")
                    result = self.fs.apply_patch("/a", patch.decode("ascii"))
                    self.assertEqual(b"cat" + newline, target.read_bytes())
                    self.assertEqual(1, result["replacements"])
                    self.assertEqual(1, self.client.renames)

    def test_invalid_text_inputs(self):
        target = self.root / "a"
        target.write_bytes(b"cat")
        calls = [lambda: self.fs.edit_file("/a", None, "dog"),
                 lambda: self.fs.edit_file("/a", "cat", None),
                 lambda: self.fs.apply_patch("/a", None)]
        for surrogate in (chr(0xd800), chr(0xdc00)):
            calls.extend([lambda s=surrogate: self.fs.edit_file("/a", s, "dog"),
                          lambda s=surrogate: self.fs.edit_file("/a", "cat", s),
                          lambda s=surrogate: self.fs.apply_patch("/a", s)])
        for call in calls:
            with self.assertRaises(Exception) as caught:
                call()
            self.assertEqual("invalid_input", getattr(caught.exception, "code", None))
            self.assertEqual("unchanged", getattr(caught.exception, "outcome", None))
            self.assertEqual(b"cat", target.read_bytes())

    def test_storage_failures_leave_original_or_report_unknown(self):
        for operation in ("edit", "patch"):
            for fault in ("create", "write", "close", "attributes", "access", "unknown", "cleanup"):
                with self.subTest(operation=operation, fault=fault):
                    client = FaultClient(self.root, fault)
                    fs = AgentFS(client)
                    target = self.root / "a"
                    target.write_bytes(b"cat\n")
                    before = target.stat()
                    with self.assertRaises(Exception) as caught:
                        if operation == "edit":
                            fs.edit_file("/a", "cat", "dog")
                        else:
                            fs.apply_patch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n")
                    error = caught.exception
                    unknown = fault == "unknown"
                    self.assertEqual("commit_unknown" if unknown else "storage_error", getattr(error, "code", None))
                    self.assertEqual("unknown" if unknown else "unchanged", getattr(error, "outcome", None))
                    self.assertEqual(b"dog\n" if unknown else b"cat\n", target.read_bytes())
                    self.assertEqual(1 if unknown else 0, client.renames)
                    self.assertTrue(error.stage)
                    if not unknown:
                        self.assertEqual(before.st_ino, target.stat().st_ino)
                    if fault == "cleanup":
                        self.assertIsNotNone(error.cleanup_error)
                        self.assertTrue(error.temp_path)

    def test_object_rejection_for_both_entries(self):
        target = self.root / "a"
        target.write_bytes(b"cat\n")
        before = target.stat()
        for kind in ("symlink", "hardlink", "xattr", "temp_xattr"):
            for operation in ("edit", "patch"):
                with self.subTest(kind=kind, operation=operation):
                    info = SimpleNamespace(**{name: getattr(before, name) for name in
                        ("st_mode", "st_nlink", "st_uid", "st_gid", "st_size", "st_ino")})
                    if kind == "symlink": info.st_mode = 0o120777
                    if kind == "hardlink": info.st_nlink = 2
                    def attrs(path):
                        return ["user.synthetic"] if (kind == "xattr" and path == "/a") or (kind == "temp_xattr" and path != "/a") else []
                    with patch.object(self.client, "lstat", side_effect=lambda p: info if p == "/a" else self.client._path(p).lstat()), patch.object(self.client, "listxattr", side_effect=attrs):
                        with self.assertRaises(Exception) as caught:
                            if operation == "edit": self.fs.edit_file("/a", "cat", "dog")
                            else: self.fs.apply_patch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n")
                    self.assertEqual("unsupported", getattr(caught.exception, "code", None))
                    self.assertEqual("unchanged", getattr(caught.exception, "outcome", None))
                    self.assertEqual(b"cat\n", target.read_bytes())
                    self.assertEqual(before.st_ino, target.stat().st_ino)

    def test_cross_chunk_long_pattern_and_short_io(self):
        for old in ("target", "a" * ((1 << 20) + 3) + "b"):
            payload = b"x" * ((1 << 20) - 3) + old.encode() + bytes([255])
            (self.root / "a").write_bytes(payload)
            original_open = self.client.open
            def open_short(path, mode):
                return ShortIO(original_open(path, mode))
            with patch.object(self.client, "open", side_effect=open_short):
                self.fs.edit_file("/a", old, "dog")
            self.assertEqual(b"x" * ((1 << 20) - 3) + b"dog" + bytes([255]), (self.root / "a").read_bytes())


    def test_zero_write_and_premature_eof_leave_target_unchanged(self):
        for fault in ("zero_write", "early_eof"):
            for operation in ("edit", "patch"):
                with self.subTest(fault=fault, operation=operation):
                    target = self.root / "a"
                    target.write_bytes(b"cat\n")
                    before = target.stat()
                    original_open = self.client.open
                    class BrokenStream(ShortIO):
                        def write(self, data):
                            return 0 if fault == "zero_write" else super().write(data)
                        def read(self, count=-1):
                            if fault == "early_eof": return b""
                            return super().read(count)
                    with patch.object(self.client, "open", side_effect=lambda p, m: BrokenStream(original_open(p, m))):
                        with self.assertRaises(Exception) as caught:
                            if operation == "edit": self.fs.edit_file("/a", "cat", "dog")
                            else: self.fs.apply_patch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n")
                    self.assertEqual("storage_error", getattr(caught.exception, "code", None))
                    self.assertEqual("unchanged", caught.exception.outcome)
                    self.assertEqual(b"cat\n", target.read_bytes())
                    self.assertEqual(before.st_ino, target.stat().st_ino)
                    self.assertEqual(before.st_mode, target.stat().st_mode)


    def test_sticky_nonowner_rejected_before_temporary_content_write(self):
        for operation in ("edit", "patch"):
            with self.subTest(operation=operation):
                target = self.root / "a"
                target.write_bytes(b"cat\n")
                client = StickyClient(self.root)
                fs = AgentFS(client)
                with self.assertRaises(Exception) as caught:
                    if operation == "edit": fs.edit_file("/a", "cat", "dog")
                    else: fs.apply_patch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n")
                self.assertEqual("storage_error", getattr(caught.exception, "code", None))
                self.assertEqual("unchanged", caught.exception.outcome)
                self.assertEqual(0, client.written)
                self.assertEqual(0, client.renames)
                self.assertEqual(b"cat\n", target.read_bytes())


    def test_sticky_allowed_native_identities_can_edit(self):
        for identity, parent_owner, target_owner in ((0, 1002, 1003), (1001, 1001, 1003), (1001, 1002, 1001)):
            for operation in ("edit", "patch"):
                with self.subTest(identity=identity, parent_owner=parent_owner, target_owner=target_owner, operation=operation):
                    target = self.root / "a"
                    target.write_bytes(b"cat\n")
                    client = StickyClient(self.root, identity, parent_owner, target_owner)
                    fs = AgentFS(client)
                    if operation == "edit": fs.edit_file("/a", "cat", "dog")
                    else: fs.apply_patch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n")
                    self.assertEqual(b"dog\n", target.read_bytes())
                    self.assertEqual(1, client.renames)


class StickyClient(EditLocalClient):
    def __init__(self, root, identity=1001, parent_owner=0, target_owner=0):
        super().__init__(root)
        self.written = 0
        self.owner_changed = False
        self.identity, self.parent_owner, self.target_owner = identity, parent_owner, target_owner

    def lstat(self, path):
        original = super().lstat(path)
        fields = {name: getattr(original, name) for name in
                  ("st_mode", "st_nlink", "st_uid", "st_gid", "st_size", "st_ino")}
        fields["st_uid"] = self.parent_owner if path == "/" else self.target_owner if path == "/a" or self.owner_changed else self.identity
        fields["st_gid"] = 0
        if path == "/": fields["st_mode"] = 0o041777
        if path == "/a": fields["st_mode"] = 0o100666
        return SimpleNamespace(**fields)

    stat = lstat

    def chown(self, path, uid, gid):
        self.owner_changed = True

    def open(self, path, mode):
        output = super().open(path, mode)
        if "x" not in mode: return output
        client = self
        class CountStream(ShortIO):
            def write(self, data):
                client.written += len(data)
                return super().write(data)
        return CountStream(output)

    def rename(self, source, target):
        if self.identity not in (0, self.parent_owner, self.target_owner):
            self.renames += 1
            raise PermissionError("synthetic sticky ownership refusal")
        super().rename(source, target)


class ShortIO:
    def __init__(self, stream): self.stream = stream
    def __enter__(self): return self
    def __exit__(self, *args): self.close()
    def __getattr__(self, name): return getattr(self.stream, name)
    def close(self): self.stream.close()
    def read(self, count=-1):
        if count < 0: raise AssertionError("unbounded read")
        return self.stream.read(min(count, 8191))
    def write(self, data): return self.stream.write(data[:8191])


class FaultClient(EditLocalClient):
    def __init__(self, root, fault):
        super().__init__(root)
        self.fault = fault

    def trip(self, stage):
        if self.fault == stage: raise OSError("synthetic " + stage)

    def access(self, path, mode):
        self.trip("access")

    def open(self, path, mode):
        if "x" not in mode: return super().open(path, mode)
        self.trip("create")
        client = self
        class FaultStream(ShortIO):
            def write(self, data):
                client.trip("write")
                client.trip("cleanup")
                return super().write(data)
            def close(self):
                super().close()
                client.trip("close")
        return FaultStream(super().open(path, mode))

    def chmod(self, path, mode):
        self.trip("attributes")
        super().chmod(path, mode)

    def rename(self, source, target):
        super().rename(source, target)
        self.trip("unknown")

    def unlink(self, path):
        self.trip("cleanup")
        super().unlink(path)


if __name__ == "__main__":
    unittest.main()
