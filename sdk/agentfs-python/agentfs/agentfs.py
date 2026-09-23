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

"""Agent-oriented byte file operations over an existing agentfs.Client."""

from posixpath import normpath
from stat import S_ISDIR, S_ISREG

from .search import READ_SIZE, RawSearchProvider, _positive, _path_key


class AgentFS:
    """A small Agent-facing facade over an existing ``agentfs.Client``.

    The caller owns Client credentials and lifetime. This facade preserves the
    Client's path and authorization semantics; it is not a sandbox boundary.
    """

    def __init__(self, client, search_provider=None):
        self.client = client
        self.search_provider = RawSearchProvider() if search_provider is None else search_provider

    def read_file(self, path, offset=0, limit=65536):
        """Read a byte range without lossy text decoding."""
        path = self._normalize_path(path, "path")
        _positive(limit, "limit")
        if not isinstance(offset, int) or isinstance(offset, bool) or offset < 0:
            raise ValueError("offset must be a nonnegative integer")
        with self.client.open(path, "rb") as stream:
            stream.seek(offset)
            data = stream.read(limit + 1)
        return {"path": path, "data": data[:limit], "offset": offset,
                "next_offset": offset + min(len(data), limit),
                "truncated": len(data) > limit}

    def write_file(self, path, data):
        """Replace a file and report the number of bytes written."""
        path = self._normalize_path(path, "path")
        if not isinstance(data, bytes):
            raise TypeError("data must be bytes")
        with self.client.open(path, "wb") as stream:
            written = 0
            while written < len(data):
                count = stream.write(data[written:])
                if not count:
                    raise OSError("write made no progress")
                written += count
        return {"path": path, "bytes_written": written}

    def list_directory(self, path, offset=0, limit=100):
        """List one deterministic page; listing pagination is not a snapshot."""
        path = self._normalize_path(path, "path")
        _positive(limit, "limit")
        if not isinstance(offset, int) or isinstance(offset, bool) or offset < 0:
            raise ValueError("offset must be a nonnegative integer")
        entries = sorted(self.client.listdir(path, detail=True), key=lambda item: _path_key(item[0]))
        page = [{"name": name, "size": info.st_size,
                 "type": "directory" if S_ISDIR(info.st_mode) else
                         "file" if S_ISREG(info.st_mode) else "other"}
                for name, info in entries[offset:offset + limit]]
        return {"entries": page, "truncated": offset + limit < len(entries),
                "next_offset": offset + len(page)}

    def edit_file(self, path, old_text, new_text):
        """Replace one exact UTF-8 occurrence in an ordinary file.

        Queue the entire read/generate/edit cycle for each file. EditError
        reports unchanged or unknown; an unknown commit must not be retried
        blindly. Unmodified bytes and basic owner/mode are preserved.
        """
        from .edit import edit_file
        return edit_file(self.client, path, old_text, new_text)

    def apply_patch(self, path, patch):
        """Apply one strict context patch after validating every block.

        Only the documented Update File subset is supported. Matching uses
        original bytes, with no fuzzy whitespace or automatic retry.
        """
        from .edit import apply_patch
        return apply_patch(self.client, path, patch)

    def search(self, query, root="/", limit=100, cursor=None):
        """Search using the configured provider; the default is raw scanning.

        Inspect complete/skipped and consume next_cursor before claiming no
        match. Built-in providers observe live files, not a filesystem snapshot.
        """
        if isinstance(root, str) and root.startswith("//"):
            raise ValueError("root must be an absolute path")
        root = self._normalize_path(root, "root")
        return self.search_provider.search(self.client, query, root, limit, cursor)

    @staticmethod
    def _normalize_path(path, name):
        if not isinstance(path, str) or not path.startswith("/"):
            raise ValueError(f"{name} must be an absolute path")
        if "\0" in path:
            raise ValueError(f"{name} must not contain NUL")
        return normpath(path)
