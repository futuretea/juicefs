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

import base64
import errno
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agentfs_test import AgentFSTest, LocalClient
from agentfs import AgentFS, RawSearchProvider, RipgrepSearchProvider, SearchProvider, SearchProviderError


class RipgrepAgentFSTest(AgentFSTest):
    def setUp(self):
        super().setUp()
        self.fs = AgentFS(self.client, search_provider=RipgrepSearchProvider())


class SearchProviderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = LocalClient(self.root)
        self.providers = [RawSearchProvider(), RipgrepSearchProvider()]

    def test_all_offsets_match_independent_oracle(self):
        payloads = {
            "a": b"aaaa\na\na\na\n\x00\xff" + "你好你好".encode() + b"\xef\xbb\xbf",
            ".hidden": b"--help [a].* \x00 target\r\ntarget\n",
            "bom": b"\xff\xfea\x00b\x00 \xef\xbb\xbftarget",
        }
        for name, content in payloads.items():
            (self.root / name).write_bytes(content)
        queries = ["aa", "a\na", "\n", "\x00", "你好", "--help", "[a].*", "target\r\n", "absent"]
        for query in queries:
            needle = query.encode()
            expected = [
                ("/" + name, offset, len(needle))
                for name, content in sorted(payloads.items())
                for offset in range(len(content))
                if content[offset:offset + len(needle)] == needle
            ]
            for provider in self.providers:
                with self.subTest(query=query, provider=type(provider).__name__):
                    fs = AgentFS(self.client, search_provider=provider)
                    found, cursor = [], None
                    while True:
                        page = fs.search(query, limit=2, cursor=cursor)
                        self.assertEqual([], page["skipped"])
                        found.extend((m["path"], m["offset"], m["length"]) for m in page["matches"])
                        cursor = page["next_cursor"]
                        if cursor is None:
                            self.assertTrue(page["complete"])
                            break
                        self.assertFalse(page["complete"])
                    self.assertEqual(expected, found)

    def test_cursors_bind_provider_and_root(self):
        (self.root / "a").write_bytes(b"aa")
        raw, rg = [AgentFS(self.client, search_provider=p) for p in self.providers]
        for source, target in [(raw, rg), (rg, raw)]:
            cursor = source.search("a", limit=1)["next_cursor"]
            with self.assertRaises(ValueError):
                target.search("a", cursor=cursor)
            with self.assertRaises(ValueError):
                source.search("a", root="/other", cursor=cursor)

    def test_provider_injection(self):
        observed = []

        class CustomProvider(SearchProvider):
            def search(self, client, query, root="/", limit=100, cursor=None):
                observed.append((client, query, root, limit, cursor))
                return {"matches": [], "next_cursor": None, "complete": True, "skipped": []}

        fs = AgentFS(self.client, search_provider=CustomProvider())
        self.assertTrue(fs.search("query", root="/dir", limit=7)["complete"])
        self.assertEqual([(self.client, "query", "/dir", 7, None)], observed)
        fs.write_file("/a", b"hello")
        self.assertEqual(b"hello", fs.read_file("/a")["data"])

    def test_direct_providers_reject_embedded_nul_root(self):
        for provider in self.providers:
            with self.subTest(provider=type(provider).__name__):
                with self.assertRaises(ValueError) as caught:
                    provider.search(self.client, "needle", root="/safe\0.suffix")
                self.assertIn("NUL", str(caught.exception))

    def test_ripgrep_failure_is_not_empty_success(self):
        (self.root / "a").write_bytes(b"needle")
        provider = RipgrepSearchProvider(executable=str(self.root / "not-installed"))
        with self.assertRaises(SearchProviderError) as caught:
            AgentFS(self.client, search_provider=provider).search("needle")
        self.assertEqual(str(caught.exception), "could not execute ripgrep")
        provider = RipgrepSearchProvider(executable="/bin/false")
        # Exit 1 means no match for rg. A real rg parse error exits 2.
        with patch("agentfs.ripgrep.subprocess.run") as run:
            run.return_value.returncode = 2
            with self.assertRaises(SearchProviderError) as caught:
                AgentFS(self.client, search_provider=provider).search("needle")
            self.assertEqual(str(caught.exception), "ripgrep failed (exit 2)")

    def test_ripgrep_exit_code_decides_despite_stderr(self):
        (self.root / "a").write_bytes(b"needle")
        provider = RipgrepSearchProvider(executable="/bin/false")
        with patch("agentfs.ripgrep.subprocess.run") as run:
            run.return_value.returncode = 1
            run.return_value.stdout = b""
            run.return_value.stderr = b"warning noise"
            page = AgentFS(self.client, search_provider=provider).search("needle")
            self.assertTrue(page["complete"])
            self.assertEqual([], page["matches"])
        with patch("agentfs.ripgrep.subprocess.run") as run:
            run.return_value.returncode = 2
            run.return_value.stdout = b""
            run.return_value.stderr = b"bad pattern"
            with self.assertRaises(SearchProviderError) as caught:
                AgentFS(self.client, search_provider=provider).search("needle")
            self.assertEqual(str(caught.exception), "ripgrep failed (exit 2)")

    def test_ripgrep_output_is_strictly_validated(self):
        (self.root / "a").write_bytes(b"needle needle needle")
        provider = RipgrepSearchProvider(executable="/bin/false")
        cases = [
            (b"garbage\n", "invalid ripgrep offset output"),
            (b"3:\n", "invalid ripgrep offset output"),
            (b"2:X\n1:X\n", "invalid ripgrep match offset"),
            (b"1:X\n1:X\n", "invalid ripgrep match offset"),
            (b"15:X\n", "invalid ripgrep match offset"),
        ]
        for stdout, message in cases:
            with self.subTest(stdout=stdout), patch("agentfs.ripgrep.subprocess.run") as run:
                run.return_value.returncode = 0
                run.return_value.stdout = stdout
                with self.assertRaises(SearchProviderError) as caught:
                    AgentFS(self.client, search_provider=provider).search("needle")
                self.assertEqual(str(caught.exception), message)

    def test_ripgrep_ignores_user_config_and_updates(self):
        config = self.root / "rg-config"
        config.write_text("--ignore-case\n", encoding="utf-8")
        (self.root / "a").write_bytes(b"Needle")
        fs = AgentFS(self.client, search_provider=RipgrepSearchProvider())
        with patch.dict(os.environ, {"RIPGREP_CONFIG_PATH": str(config)}):
            self.assertEqual([], fs.search("needle")["matches"])
            fs.write_file("/a", b"needle")
            self.assertEqual([{"path": "/a", "offset": 0, "length": 6}], fs.search("needle")["matches"])


class FixtureClient(LocalClient):
    """LocalClient with fixture-driven faults injected on read opens."""

    def __init__(self, root, read_error=False, mutate=None):
        super().__init__(root)
        self.read_error = read_error
        self.mutate = mutate
        self.mutated = False

    def open(self, path, mode):
        if "r" in mode:
            if self.read_error:
                raise PermissionError(errno.EACCES, "injected read error")
            if self.mutate and not self.mutated and path == self.mutate["path"]:
                self.mutated = True
                target = self._path(path)
                target.write_bytes(base64.b64decode(self.mutate["content_b64"]))
                if "mtime" in self.mutate:
                    os.utime(target, (self.mutate["mtime"], self.mutate["mtime"]))
        return super().open(path, mode)


class SharedSearchFixtureTest(unittest.TestCase):
    """Run the canonical search contract cases from sdk/agentfs/fixtures."""

    FIXTURE = Path(__file__).resolve().parents[2] / "agentfs/fixtures/search_cases.json"
    EXPECTED_CASES = 17
    SUPPORTS = {"set_mtime", "inject_read_error", "symlink", "mutate_read"}
    MAX_SKIPPED = 0

    @classmethod
    def setUpClass(cls):
        cls.cases = json.loads(cls.FIXTURE.read_text(encoding="utf-8"))["cases"]

    def test_fixture_case_count(self):
        self.assertEqual(self.EXPECTED_CASES, len(self.cases))

    def test_shared_search_cases(self):
        providers = [("raw", RawSearchProvider()), ("ripgrep", RipgrepSearchProvider())]
        skipped = []
        for provider_name, provider in providers:
            for case in self.cases:
                unsupported = set(case.get("requires", [])) - self.SUPPORTS
                if unsupported:
                    skipped.append((provider_name, case["id"], sorted(unsupported)))
                    continue
                with self.subTest(provider=provider_name, case=case["id"]):
                    self.run_case(provider_name, provider, case)
        self.assertLessEqual(len(skipped), self.MAX_SKIPPED,
                             f"unsupported cases skipped: {skipped}")

    def run_case(self, provider_name, provider, case):
        requires = set(case.get("requires", []))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for entry in case.get("files", []):
                target = root / entry["path"].lstrip("/")
                target.parent.mkdir(parents=True, exist_ok=True)
                if "symlink_to" in entry:
                    os.symlink(entry["symlink_to"], target)
                else:
                    target.write_bytes(base64.b64decode(entry["content_b64"]))
                if "mtime" in entry:
                    os.utime(target, (entry["mtime"], entry["mtime"]))
            client = FixtureClient(root, read_error="inject_read_error" in requires,
                                   mutate=case.get("mutate") if "mutate_read" in requires else None)
            fs = AgentFS(client, search_provider=provider)
            cursor = None
            for expected_page in case.get("pages", []):
                page = fs.search(case["query"], root=case.get("root", "/"),
                                 limit=case.get("limit", 100), cursor=cursor)
                self.assertEqual(expected_page["matches"], page["matches"])
                self.assertEqual(expected_page.get("skipped", []), page["skipped"])
                self.assertEqual(expected_page["complete"], page["complete"])
                self.assertEqual(expected_page["next_cursor"], page["next_cursor"] is not None)
                if "expect_next_cursor" in expected_page:
                    self.assertEqual(expected_page["expect_next_cursor"][provider_name],
                                     page["next_cursor"])
                cursor = page["next_cursor"]
            if "error" in case:
                with self.assertRaises(ValueError) as caught:
                    fs.search(case["query"], root=case.get("root", "/"),
                              limit=case.get("limit", 100))
                self.assertEqual(case["error"]["message"], str(caught.exception))
            for follow in case.get("then", []):
                with self.assertRaises(ValueError) as caught:
                    fs.search(follow.get("query", case["query"]),
                              root=follow.get("root", case.get("root", "/")),
                              limit=follow.get("limit", case.get("limit", 100)),
                              cursor=self.pick(follow.get("cursor", cursor), provider_name))
                self.assertEqual(follow["error"]["message"], str(caught.exception))

    @staticmethod
    def pick(value, provider_name):
        return value[provider_name] if isinstance(value, dict) else value


if __name__ == "__main__":
    unittest.main()
