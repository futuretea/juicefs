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

"""Unbuffered byte streams for the AgentFS native client."""

import ctypes
import io
import os

from ._native import thread_id


_CHUNK_SIZE = 4 << 20


class Stream:
    def __init__(self, client, fd, mode, length):
        self.client = client
        self.fd = fd
        self.mode = mode
        self.length = length
        self.offset = 0
        self.closed = False

    def _check_open(self):
        if self.closed or not getattr(self.client, "h", 0):
            raise ValueError("I/O operation on closed file")

    def read(self, size=-1):
        self._check_open()
        if self.mode != "rb":
            raise io.UnsupportedOperation("not readable")
        if size == 0:
            return b""
        chunks = []
        remaining = size
        while remaining != 0:
            count = _CHUNK_SIZE if remaining < 0 else min(remaining, _CHUNK_SIZE)
            buffer = ctypes.create_string_buffer(count)
            received = self.client.lib.jfs_pread(
                thread_id(), ctypes.c_int32(self.fd), ctypes.cast(buffer, ctypes.c_void_p),
                ctypes.c_int32(count), ctypes.c_int64(self.offset))
            if received == 0:
                break
            chunks.append(buffer.raw[:received])
            self.offset += received
            if remaining > 0:
                remaining -= received
        return b"".join(chunks)

    def write(self, data):
        self._check_open()
        if self.mode == "rb":
            raise io.UnsupportedOperation("not writable")
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise TypeError("a bytes-like object is required")
        data = bytes(data[:_CHUNK_SIZE])
        if not data:
            return 0
        written = self.client.lib.jfs_pwrite(
            thread_id(), ctypes.c_int32(self.fd), data,
            ctypes.c_int32(len(data)), ctypes.c_int64(self.offset))
        self.offset += written
        self.length = max(self.length, self.offset)
        return written

    def seek(self, offset, whence=os.SEEK_SET):
        self._check_open()
        if whence == os.SEEK_SET:
            position = offset
        elif whence == os.SEEK_CUR:
            position = self.offset + offset
        elif whence == os.SEEK_END:
            position = self.length + offset
        else:
            raise ValueError("invalid whence")
        if position < 0:
            raise ValueError("negative seek position")
        self.offset = position
        return position

    def tell(self):
        self._check_open()
        return self.offset

    def close(self):
        if not self.closed:
            try:
                self.client.lib.jfs_close(thread_id(), ctypes.c_int32(self.fd))
            finally:
                self.closed = True

    def __enter__(self):
        self._check_open()
        return self

    def __exit__(self, _type, _value, _traceback):
        self.close()

    def __del__(self):
        if not getattr(self, "closed", True):
            try:
                self.close()
            except Exception:
                pass
