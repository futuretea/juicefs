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

import errno
import io
import os
import tempfile
import unittest
from pathlib import Path

from agentfs.agentfs import AgentFS, READ_SIZE


class LocalClient:
    """Exercise the Client contract without a mounted filesystem."""

    def __init__(self, root):
        self.root = Path(root)

    def _path(self, path):
        return self.root / path.lstrip("/")

    def open(self, path, mode):
        return self._path(path).open(mode)

    def stat(self, path):
        return self._path(path).stat()

    def listdir(self, path, detail=False):
        return [(entry.name, entry.lstat()) for entry in self._path(path).iterdir()]


class AgentFSTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = LocalClient(self.root)
        self.fs = AgentFS(self.client)

    def put(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    def test_read_write_and_listing(self):
        self.fs.write_file("/b.txt", "你好 world".encode())
        self.fs.write_file("/a.txt", b"abc")
        result = self.fs.read_file("/b.txt", 0, 6)
        self.assertEqual(result["data"].decode(), "你好")
        self.assertTrue(result["truncated"])
        self.assertEqual(self.fs.read_file("/b.txt", offset=result["next_offset"])["data"], b" world")
        page = self.fs.list_directory("/", limit=1)
        self.assertEqual(page["entries"][0]["name"], "a.txt")
        self.assertTrue(page["truncated"])
        self.assertEqual(self.fs.list_directory("/", offset=page["next_offset"])["entries"][0]["name"], "b.txt")
        with self.assertRaises(NotADirectoryError):
            self.fs.list_directory("/a.txt")

    def test_search_returns_utf8_byte_offsets(self):
        payload = "前缀 needle\t你好\nneedle\t你好".encode()
        self.put("nested/a.txt", payload)
        result = self.fs.search("needle\t你好")
        expected = [payload.find("needle\t你好".encode()), payload.rfind("needle\t你好".encode())]
        self.assertEqual([(match["path"], match["offset"], match["length"])
                          for match in result["matches"]],
                         [("/nested/a.txt", offset, len("needle\t你好".encode())) for offset in expected])
        self.assertTrue(result["complete"])
        self.assertEqual([], result["skipped"])

    def test_search_paginates_without_duplicate_matches(self):
        self.put("a", b"needle needle")
        self.put("b", b"needle")
        cursor, matches = None, []
        while True:
            page = self.fs.search("needle", limit=1, cursor=cursor)
            matches.extend((item["path"], item["offset"]) for item in page["matches"])
            cursor = page["next_cursor"]
            if not cursor:
                self.assertTrue(page["complete"])
                break
        self.assertEqual([("/a", 0), ("/a", 7), ("/b", 0)], matches)
        first = self.fs.search("needle", limit=1)
        with self.assertRaises(ValueError):
            self.fs.search("other", cursor=first["next_cursor"])

    def test_search_handles_chunk_boundary_and_binary_contents(self):
        needle = b"target"
        self.put("large", b"x" * (READ_SIZE - 3) + needle + b"\xff")
        result = self.fs.search("target")
        self.assertEqual(result["matches"], [{"path": "/large", "offset": READ_SIZE - 3, "length": len(needle)}])
        self.assertTrue(result["complete"])

    def test_search_discloses_read_errors_and_changes(self):
        self.put("a", b"target")

        class BrokenClient(LocalClient):
            def open(self, path, mode):
                raise PermissionError(errno.EACCES, "denied")

        broken = AgentFS(BrokenClient(self.root)).search("target")
        self.assertFalse(broken["complete"])
        self.assertEqual(broken["skipped"], [{"path": "/a", "reason": "read_error", "errno": errno.EACCES}])

        class ChangingClient(LocalClient):
            def open(self, path, mode):
                stream = super().open(path, mode)
                if path == "/a" and "r" in mode:
                    self._path(path).write_bytes(b"replacement target target")
                return stream

        changed = AgentFS(ChangingClient(self.root)).search("target")
        self.assertFalse(changed["complete"])
        self.assertEqual(changed["matches"], [])
        self.assertEqual(changed["skipped"], [{"path": "/a", "reason": "changed"}])

    def test_s6_native_stat_shape_detects_same_size_mtime_change(self):
        self.put("a", b"target")
        os.utime(self.root / "a", (1000, 1000))

        class NativeShapeClient(LocalClient):
            def stat(self, path):
                return os.stat_result(tuple(super().stat(path))[:10])

            def open(self, path, mode):
                stream = super().open(path, mode)
                if path == "/a" and "r" in mode:
                    os.utime(self._path(path), (1001, 1001))
                return stream

        result = AgentFS(NativeShapeClient(self.root)).search("target")
        self.assertEqual([], result["matches"])
        self.assertEqual([{"path": "/a", "reason": "changed"}], result["skipped"])
        self.assertFalse(result["complete"])

    def test_search_ignores_ctime_only_change(self):
        self.put("meta", b"target")

        class CtimeClient(LocalClient):
            def open(self, path, mode):
                stream = super().open(path, mode)
                if path == "/meta" and "r" in mode:
                    target = self._path(path)
                    os.chmod(target, target.stat().st_mode ^ 0o100)
                return stream

        result = AgentFS(CtimeClient(self.root)).search("target")
        self.assertTrue(result["complete"])
        self.assertEqual(result["matches"], [{"path": "/meta", "offset": 0, "length": 6}])
        self.assertEqual(result["skipped"], [])

    def test_search_marks_file_root_as_non_directory(self):
        self.put("a", b"target")
        result = self.fs.search("target", root="/a")
        self.assertFalse(result["complete"])
        self.assertEqual(result["matches"], [])
        self.assertEqual(result["skipped"], [{"path": "/a", "reason": "non_directory_root"}])

        class UnlistableClient(LocalClient):
            def listdir(self, path, detail=False):
                raise PermissionError(errno.EACCES, "denied")

        unlistable = AgentFS(UnlistableClient(self.root)).search("target")
        self.assertFalse(unlistable["complete"])
        self.assertEqual(unlistable["skipped"], [{"path": "/", "reason": "list_error", "errno": errno.EACCES}])

    def test_search_rejects_double_slash_root(self):
        for root in ("//", "//nested", "///", "///nested"):
            with self.subTest(root=root), self.assertRaises(ValueError) as caught:
                self.fs.search("needle", root=root)
            self.assertEqual(str(caught.exception), "root must be an absolute path")

    def test_path_operations_reject_embedded_nul_before_client_io(self):
        cases = [
            lambda: self.fs.read_file("/safe\0.suffix"),
            lambda: self.fs.write_file("/safe\0.suffix", b"unexpected"),
            lambda: self.fs.list_directory("/safe\0.suffix"),
            lambda: self.fs.search("needle", root="/safe\0.suffix"),
        ]
        for operation in cases:
            with self.subTest(operation=operation):
                with self.assertRaises(ValueError) as caught:
                    operation()
                self.assertIn("NUL", str(caught.exception))
        self.assertFalse((self.root / "safe").exists())

    def test_search_normalizes_root_before_custom_provider(self):
        seen = []

        class RecordingProvider:
            def search(self, client, query, root, limit, cursor):
                seen.append(root)
                return {"matches": [], "skipped": [], "complete": True, "next_cursor": None}

        fs = AgentFS(LocalClient(self.root), RecordingProvider())
        self.assertTrue(fs.search("needle", root="/nested/")["complete"])
        self.assertEqual(seen, ["/nested"])
        with self.assertRaises(ValueError) as caught:
            fs.search("needle", root="//nested")
        self.assertEqual(str(caught.exception), "root must be an absolute path")
        self.assertEqual(seen, ["/nested"])

    def test_search_cursor_offset_must_be_ascii_integer(self):
        self.put("a", b"needle needle")
        cursor = self.fs.search("needle", limit=1)["next_cursor"]
        parts = cursor.split(".")
        for offset in (" 1", "１", "1_0", "9223372036854775808"):
            parts[4] = offset
            with self.subTest(offset=offset), self.assertRaises(ValueError) as caught:
                self.fs.search("needle", cursor=".".join(parts))
            self.assertEqual(str(caught.exception), "invalid cursor")
        parts[4] = "+1"
        page = self.fs.search("needle", cursor=".".join(parts))
        self.assertEqual(page["matches"], [{"path": "/a", "offset": 7, "length": 6}])

    def test_search_cursor_mismatch_messages(self):
        self.put("a", b"needle")
        cursor = self.fs.search("needle", limit=1)["next_cursor"]
        with self.assertRaises(ValueError) as caught:
            self.fs.search("other", cursor=cursor)
        self.assertEqual(str(caught.exception), "cursor does not belong to this search")
        with self.assertRaises(ValueError) as caught:
            self.fs.search("needle", root="/other", cursor=cursor)
        self.assertEqual(str(caught.exception), "cursor does not belong to this search")
        with self.assertRaises(ValueError) as caught:
            self.fs.search("needle", cursor="garbage")
        self.assertEqual(str(caught.exception), "invalid cursor")

    def test_invalid_inputs_and_short_writes(self):
        for query in ("", None):
            with self.subTest(query=query), self.assertRaises(ValueError):
                self.fs.search(query)
        for query in ("\ud800", "\udc00"):
            with self.subTest(query=query), self.assertRaises(ValueError):
                self.fs.search(query)
        self.put("replacement", "\ufffd".encode())
        self.assertEqual(self.fs.search("\ufffd")["matches"],
                         [{"path": "/replacement", "offset": 0, "length": 3}])
        for root in ("relative", None):
            with self.subTest(root=root), self.assertRaises(ValueError):
                self.fs.search("needle", root=root)
        for limit in (0, -1, True):
            with self.subTest(limit=limit), self.assertRaises(ValueError):
                self.fs.search("needle", limit=limit)

        class ShortStream(io.BytesIO):
            def write(self, data):
                return super().write(data[:2])

            def close(self):
                pass

        class Client:
            def open(self, path, mode):
                return stream

        stream = ShortStream()
        self.assertEqual(AgentFS(Client()).write_file("/a", b"abcdef")["bytes_written"], 6)
        self.assertEqual(stream.getvalue(), b"abcdef")
        with self.assertRaises(ValueError):
            self.fs.read_file("relative")


if __name__ == "__main__":
    unittest.main()
