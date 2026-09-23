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

"""The explicitly supported single-file context-patch subset."""

import re

from .edit import EditError, normalize_path
from .edit_match import matches, read_exact, unique


def _content(line):
    return line[:-2] if line.endswith(b"\r\n") else line[:-1] if line.endswith(b"\n") else line


def _block(anchor, lines, eof):
    if not lines or not any(line[:1] in (b"+", b"-") for line in lines):
        raise EditError("invalid_patch")
    old = b"".join(line[1:] for line in lines if line[:1] != b"+")
    new = b"".join(line[1:] for line in lines if line[:1] != b"-")
    if not old and not eof:
        raise EditError("invalid_patch")
    end, last_output_context_end = 0, -1
    for line in lines:
        if line[:1] != b"+":
            end += len(line) - 1
        if line[:1] != b"-":
            last_output_context_end = end if line[:1] == b" " else -1
    preserve_after_ending = 0 <= last_output_context_end < len(old)
    return anchor, old, new, eof, preserve_after_ending


def parse(path, patch):
    pieces = patch.split(b"\n")
    lines = [piece + b"\n" for piece in pieces[:-1]]
    if pieces[-1]:
        lines.append(pieces[-1])
    if len(lines) < 5 or _content(lines[0]) != b"*** Begin Patch" or _content(lines[-1]) != b"*** End Patch":
        raise EditError("invalid_patch")
    header = _content(lines[1])
    if not header.startswith(b"*** Update File: "):
        raise EditError("invalid_patch")
    try:
        target = normalize_path(header[len(b"*** Update File: "):].decode("utf-8"))
    except (EditError, UnicodeError):
        raise EditError("invalid_patch") from None
    if target != path:
        raise EditError("invalid_patch")
    blocks, payload, anchor, opened, eof = [], [], None, False, False
    for line in lines[2:-1]:
        control = _content(line)
        if control == b"@@" or control.startswith(b"@@ "):
            if eof or re.fullmatch(rb"@@ -[0-9]+(?:,[0-9]+)? \+[0-9]+(?:,[0-9]+)? @@.*", control):
                raise EditError("invalid_patch")
            if opened:
                blocks.append(_block(anchor, payload, False))
            anchor = control[3:] if control != b"@@" else None
            if anchor == b"":
                raise EditError("invalid_patch")
            payload, opened = [], True
        elif control == b"*** End of File" and opened and not eof:
            eof = True
        elif opened and not eof and line[:1] in (b" ", b"-", b"+") and line.endswith(b"\n"):
            payload.append(line)
        else:
            raise EditError("invalid_patch")
    if not opened:
        raise EditError("invalid_patch")
    blocks.append(_block(anchor, payload, eof))
    return blocks


def _anchor_end(stream, anchor, start, size):
    found = []
    for ending in (b"\n", b"\r\n"):
        pattern = anchor + ending
        if start == 0 and size >= len(pattern):
            stream.seek(0)
            if read_exact(stream, len(pattern)) == pattern:
                found.append(len(pattern))
        for position in matches(stream, b"\n" + pattern, max(0, start - 1), size):
            if position + 1 >= start:
                found.append(position + 1 + len(pattern))
                if len(found) > 1:
                    raise ValueError("ambiguous_match")
    position = size - len(anchor)
    if position >= start:
        stream.seek(max(0, position - 1))
        expected = (b"\n" if position else b"") + anchor
        if read_exact(stream, len(expected)) == expected:
            found.append(size)
    if not found:
        raise ValueError("no_match")
    if len(found) != 1:
        raise ValueError("ambiguous_match")
    return found[0]


def locate(stream, size, blocks):
    terminated = False
    if size:
        stream.seek(size - 1)
        terminated = read_exact(stream, 1) == b"\n"
    spans, consumed = [], 0
    for anchor, old, new, eof, preserve_after_ending in blocks:
        start = _anchor_end(stream, anchor, consumed, size) if anchor is not None else consumed
        if eof and not terminated:
            old = _content(old)
            if not preserve_after_ending:
                new = _content(new)
        if not old:
            if size or len(blocks) != 1 or not eof:
                raise EditError("invalid_patch")
            position = 0
        elif eof:
            position = size - len(old)
            if position < start:
                raise ValueError("no_match")
            stream.seek(position)
            if read_exact(stream, len(old)) != old:
                raise ValueError("no_match")
        else:
            position = unique(stream, old, start, size)
        consumed = position + len(old)
        if old != new:
            spans.append((position, consumed, new))
    return spans
