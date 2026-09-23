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

"""Search provider contract and shared streaming traversal."""

import base64
import binascii
import hashlib
import re
from abc import ABC, abstractmethod
from hmac import compare_digest
from posixpath import join, normpath
from stat import S_ISDIR, S_ISREG

READ_SIZE = 1 << 20

# Cursor offsets follow Java Long.parseLong strictness: ASCII digits, optional '+'.
_CURSOR_OFFSET = re.compile(r"\+?[0-9]+")


def _positive(value, name):
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")


def _path_key(path):
    return path.encode("utf-8")


def _status_stamp(info):
    # Change detection compares size and mtime only; ctime is not content metadata.
    mtime_ns = getattr(info, "st_mtime_ns", None)
    return (info.st_size, info.st_mtime if mtime_ns is None else mtime_ns)


def _errno(error):
    return getattr(error, "errno", None)


class SearchProvider(ABC):
    """Implement exact UTF-8 search over the supplied filesystem.

    Providers must preserve byte offsets, overlapping matches, ordered pages,
    and disclose incomplete coverage. Cursors belong to their provider and
    query/root. File operations and client ownership stay with AgentFS.
    """

    @abstractmethod
    def search(self, client, query, root="/", limit=100, cursor=None):
        """Return matches, next_cursor, complete, and skipped."""


class RawSearchProvider(SearchProvider):
    """Search SDK streams directly; no persistent index or subprocess."""

    def search(self, client, query, root="/", limit=100, cursor=None):
        return _StreamingSearch(client, "raw", self._find_offsets).search(query, root, limit, cursor)

    @staticmethod
    def _find_offsets(window, needle, start, limit):
        for _ in range(limit):
            found = window.find(needle, start)
            if found < 0:
                return
            yield found
            start = found + 1


class _StreamingSearch:
    """Shared traversal for built-in streaming providers, not an extension API."""

    def __init__(self, client, provider, matcher):
        self.client = client
        self.cursor_version = "v1-" + provider
        self.matcher = matcher

    def search(self, query, root="/", limit=100, cursor=None):
        """Find case-sensitive UTF-8 literal matches with byte offsets.

        ``complete`` means that this observed traversal finished without known
        list/read/change skips. It does not provide a cross-call filesystem
        snapshot. Callers must inspect ``skipped`` before interpreting an empty
        page as no match.
        """
        needle = self._validate_query(query, limit)
        root = self._normalize_root(root)
        resume_path, resume_offset = self._decode_cursor(cursor, root, needle)
        files, directories, skipped = self._collect_files(root)
        paths = {path for path, _info in files}
        if resume_path and resume_path not in paths:
            skipped.append({"path": resume_path, "reason": "cursor_path_missing"})

        matches = []
        for path, _info in files:
            comparison = self._compare_paths(path, resume_path) if resume_path else 1
            if comparison < 0:
                continue
            start_after = resume_offset if comparison == 0 else None
            file_matches, reached_limit, issue = self._scan_file(
                path, needle, start_after, limit - len(matches))
            if issue:
                skipped.append(issue)
                continue
            matches.extend(file_matches)
            if reached_limit:
                last = matches[-1]
                return self._page(matches, self._encode_cursor(root, needle, last), skipped)

        skipped.extend(self._changed_directories(directories))
        return self._page(matches, None, skipped)

    @staticmethod
    def _validate_query(query, limit):
        if not isinstance(query, str) or not query:
            raise ValueError("query must be a nonempty string")
        _positive(limit, "limit")
        try:
            return query.encode("utf-8")
        except UnicodeEncodeError as error:
            raise ValueError("query must be valid UTF-8") from error

    @staticmethod
    def _normalize_path(path, name):
        if not isinstance(path, str) or not path.startswith("/") or path.startswith("//"):
            raise ValueError(f"{name} must be an absolute path")
        if "\0" in path:
            raise ValueError(f"{name} must not contain NUL")
        return normpath(path)

    @classmethod
    def _normalize_root(cls, root):
        return cls._normalize_path(root, "root")

    def _collect_files(self, root):
        pending = [root]
        directories, files, skipped = [], [], []
        while pending:
            directory = pending.pop()
            try:
                before = self.client.stat(directory)
            except OSError as error:
                skipped.append({"path": directory, "reason": "list_error", "errno": _errno(error)})
                continue
            if not S_ISDIR(before.st_mode):
                skipped.append({"path": directory, "reason": "non_directory_root"})
                continue
            try:
                entries = self.client.listdir(directory, detail=True)
            except OSError as error:
                skipped.append({"path": directory, "reason": "list_error", "errno": _errno(error)})
                continue
            directories.append((directory, before))
            children = []
            for name, info in sorted(entries, key=lambda item: _path_key(item[0])):
                path = join(directory, name)
                if S_ISDIR(info.st_mode):
                    children.append(path)
                elif S_ISREG(info.st_mode):
                    files.append((path, info))
                else:
                    skipped.append({"path": path, "reason": "nonregular"})
            pending.extend(reversed(children))
        return sorted(files, key=lambda item: _path_key(item[0])), directories, skipped

    def _scan_file(self, path, needle, start_after, remaining):
        try:
            before = self.client.stat(path)
            if not S_ISREG(before.st_mode):
                return [], False, {"path": path, "reason": "nonregular"}
            matches, reached_limit = self._find_matches(path, needle, start_after, remaining)
            after = self.client.stat(path)
        except OSError as error:
            return [], False, {"path": path, "reason": "read_error", "errno": _errno(error)}
        if _status_stamp(before) != _status_stamp(after):
            return [], False, {"path": path, "reason": "changed"}
        return [{"path": path, "offset": offset, "length": len(needle)} for offset in matches], reached_limit, None

    def _find_matches(self, path, needle, start_after, remaining):
        overlap = max(0, len(needle) - 1)
        start = max(0, start_after - overlap) if start_after is not None else 0
        matches, carry, cursor = [], b"", start
        with self.client.open(path, "rb") as stream:
            stream.seek(start)
            while True:
                block = stream.read(READ_SIZE)
                if not block:
                    return matches, False
                window = carry + block
                window_offset = cursor - len(carry)
                search_from = max(0, start_after + 1 - window_offset) if start_after is not None else 0
                for found in self.matcher(window, needle, search_from, remaining - len(matches)):
                    matches.append(window_offset + found)
                    if len(matches) == remaining:
                        return matches, True
                carry = window[-overlap:] if overlap else b""
                cursor += len(block)

    def _changed_directories(self, directories):
        skipped = []
        for path, before in directories:
            try:
                after = self.client.stat(path)
            except OSError:
                skipped.append({"path": path, "reason": "changed"})
                continue
            if _status_stamp(before) != _status_stamp(after):
                skipped.append({"path": path, "reason": "changed"})
        return skipped

    @staticmethod
    def _page(matches, next_cursor, skipped):
        return {"matches": matches, "next_cursor": next_cursor,
                "complete": not next_cursor and not skipped, "skipped": skipped}

    @staticmethod
    def _compare_paths(left, right):
        if right is None:
            return 1
        return (_path_key(left) > _path_key(right)) - (_path_key(left) < _path_key(right))

    @staticmethod
    def _digest(value):
        return hashlib.sha256(value).hexdigest()

    def _encode_cursor(self, root, needle, match):
        path = base64.urlsafe_b64encode(match["path"].encode("utf-8")).decode("ascii").rstrip("=")
        return ".".join((self.cursor_version, self._digest(root.encode("utf-8")),
                         self._digest(needle), path, str(match["offset"])))

    def _decode_cursor(self, cursor, root, needle):
        if cursor is None:
            return None, None
        if not isinstance(cursor, str):
            raise ValueError("cursor must be a string")
        parts = cursor.split(".")
        if len(parts) != 5 or parts[0] != self.cursor_version:
            raise ValueError("invalid cursor")
        if not compare_digest(parts[1], self._digest(root.encode("utf-8"))) or not compare_digest(parts[2], self._digest(needle)):
            raise ValueError("cursor does not belong to this search")
        try:
            padding = "=" * (-len(parts[3]) % 4)
            path = base64.urlsafe_b64decode(parts[3] + padding).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError) as error:
            raise ValueError("invalid cursor") from error
        if _CURSOR_OFFSET.fullmatch(parts[4]) is None:
            raise ValueError("invalid cursor")
        offset = int(parts[4])
        if offset < 0 or offset > 2**63 - 1 or not path.startswith("/"):
            raise ValueError("invalid cursor")
        return path, offset
