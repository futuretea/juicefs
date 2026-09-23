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

"""Bounded byte matching and original-file span rewriting."""

READ_SIZE = 64 << 10


def matches(stream, needle, start=0, end=None):
    """Yield overlapping matches while retaining only a pattern-sized tail."""
    stream.seek(start)
    position, tail = start, b""
    block_size = max(READ_SIZE, len(needle))
    while end is None or position < end:
        data = stream.read(block_size if end is None else min(block_size, end - position))
        if not data:
            if end is not None and position < end:
                raise OSError("read ended before expected file size")
            return
        block = tail + data
        base = position - len(tail)
        offset = block.find(needle)
        while offset >= 0:
            yield base + offset
            offset = block.find(needle, offset + 1)
        position += len(data)
        tail = block[-(len(needle) - 1):] if len(needle) > 1 else b""


def unique(stream, needle, start=0, end=None):
    found = matches(stream, needle, start, end)
    first = next(found, None)
    if first is None:
        raise ValueError("no_match")
    if next(found, None) is not None:
        raise ValueError("ambiguous_match")
    return first


def read_exact(stream, size):
    chunks = []
    while size:
        data = stream.read(size)
        if not data:
            raise OSError("read ended before expected file size")
        chunks.append(data)
        size -= len(data)
    return b"".join(chunks)


def output_chunks(stream, spans, size):
    position = 0
    for start, end, replacement in spans:
        stream.seek(position)
        while position < start:
            data = read_exact(stream, min(READ_SIZE, start - position))
            position += len(data)
            yield data
        yield replacement
        position = end
    stream.seek(position)
    while position < size:
        data = read_exact(stream, min(READ_SIZE, size - position))
        position += len(data)
        yield data
