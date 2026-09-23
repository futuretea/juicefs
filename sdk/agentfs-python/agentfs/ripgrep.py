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

"""ripgrep matching over bounded windows read through the AgentFS client."""

import io
import subprocess

from .search import SearchProvider, _StreamingSearch


class SearchProviderError(RuntimeError):
    """The search implementation failed; no successful page is returned."""


class RipgrepSearchProvider(SearchProvider):
    """Use an installed PCRE2-enabled rg executable, without a FUSE mount.

    Each SDK window is sent to a subprocess. Startup, pipe copies and matching
    are part of query cost. No automatic fallback or persistent index is used.
    The executable is trusted application configuration, not an Agent argument.
    """

    def __init__(self, executable="rg"):
        self.executable = executable

    def search(self, client, query, root="/", limit=100, cursor=None):
        return _StreamingSearch(client, "ripgrep", self._find_offsets).search(query, root, limit, cursor)

    def _find_offsets(self, window, needle, start, limit):
        # Look ahead for the literal, then consume only one byte, so overlapping
        # matches are included. Hex encoding excludes shell/regex interpretation.
        pattern = "(?=" + "".join(r"\x%02x" % byte for byte in needle) + r")[\s\S]"
        command = [
            self.executable, "--no-config", "--text", "--multiline", "--pcre2",
            "--no-unicode", "--encoding", "none", "--only-matching", "--byte-offset",
            "--no-line-number", "--no-heading", "--no-filename", "--color", "never",
            "--replace", "X", "-e", pattern, "--", "-",
        ]
        try:
            result = subprocess.run(command, input=window, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, check=False)
        except OSError as error:
            raise SearchProviderError("could not execute ripgrep") from error
        if result.returncode not in (0, 1):
            raise SearchProviderError("ripgrep failed (exit {})".format(result.returncode))
        # Output is only offsets plus a constant replacement, never file text.
        previous = -1
        for line in io.BytesIO(result.stdout):
            offset_text, separator, marker = line.rstrip(b"\n").partition(b":")
            if not offset_text.isdigit() or separator != b":" or marker != b"X":
                raise SearchProviderError("invalid ripgrep offset output")
            offset = int(offset_text)
            if offset <= previous or offset + len(needle) > len(window):
                raise SearchProviderError("invalid ripgrep match offset")
            previous = offset
            if offset >= start:
                yield offset
                limit -= 1
                if limit == 0:
                    return
