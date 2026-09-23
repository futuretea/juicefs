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

import ctypes
import os
import struct
import unittest


class ClientBoundaryTest(unittest.TestCase):
    def test_s7_close_flushes_by_default_and_invalidates_handle(self):
        from agentfs.client import Client

        class FakeLib:
            def __init__(self):
                self.calls = []

            def jfs_term(self, thread, handle, terminate):
                self.calls.append((handle.value, terminate))

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        client.close()
        client.close()
        self.assertEqual([(37, 0)], client.lib.calls)
        self.assertEqual(0, client.h)
        with self.assertRaises(ValueError):
            client.stat("/closed")

    def test_explicit_terminate_releases_native_filesystem(self):
        from agentfs.client import Client

        class FakeLib:
            def __init__(self):
                self.terminate = None

            def jfs_term(self, thread, handle, terminate):
                self.terminate = terminate

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        client.close(terminate=True)
        self.assertEqual(1, client.lib.terminate)

    def test_stat_uses_native_file_info_layout(self):
        from agentfs.client import Client
        from agentfs._native import FileInfo

        class FakeLib:
            def jfs_stat(self, thread, handle, path, output):
                info = output._obj
                info.inode = 42
                info.mode = 0o100640
                info.uid = 1000
                info.gid = 1001
                info.atime = 101
                info.mtime = 102
                info.ctime = 103
                info.nlink = 1
                info.length = 6

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        info = client.stat("/a")
        self.assertEqual(48, ctypes.sizeof(FileInfo))
        self.assertEqual((42, 0o100640, 1000, 1001, 6, 102),
                         (info.st_ino, info.st_mode, info.st_uid, info.st_gid,
                          info.st_size, info.st_mtime))

    def test_listdir_detail_decodes_and_frees_native_buffer(self):
        from agentfs.client import Client

        record = struct.pack("!H", 5) + b"a.txt"
        record += struct.pack("!IQIIIQIII", 0o100644, 7, 1, 2, 3, 4, 5, 6, 7)

        class FakeLib:
            def __init__(self):
                self.buffer = ctypes.create_string_buffer(record)
                self.freed = []

            def jfs_listdir2(self, thread, handle, path, detail, buffer, size):
                buffer._obj.value = ctypes.addressof(self.buffer)
                size._obj.value = len(record)

            def free(self, pointer):
                self.freed.append(pointer.value)

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        entries = client.listdir("/", detail=True)
        self.assertEqual(["a.txt"], [name for name, _ in entries])
        self.assertEqual((7, 4), (entries[0][1].st_ino, entries[0][1].st_size))
        self.assertEqual([ctypes.addressof(client.lib.buffer)], client.lib.freed)

    def test_wb_truncates_and_stream_closes_native_descriptor(self):
        from agentfs.client import Client

        class FakeLib:
            def __init__(self):
                self.calls = []

            def jfs_open_posix(self, thread, handle, path, size, flags):
                size._obj.value = 9
                self.calls.append("open")
                return 11

            def jfs_ftruncate(self, thread, fd, length):
                self.calls.append(("truncate", fd.value, length.value))

            def jfs_pwrite(self, thread, fd, data, length, offset):
                self.calls.append(("write", fd.value, bytes(data), offset.value))
                return length.value

            def jfs_close(self, thread, fd):
                self.calls.append(("close", fd.value))

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        with client.open("/a", "wb") as stream:
            self.assertEqual(3, stream.write(b"cat"))
        self.assertEqual(["open", ("truncate", 11, 0),
                          ("write", 11, b"cat", 0), ("close", 11)], client.lib.calls)
        self.assertTrue(stream.closed)
        with self.assertRaises(ValueError):
            stream.write(b"again")

    def test_path_operations_reject_embedded_nul_before_native_io(self):
        from agentfs.client import Client

        class FakeLib:
            def __getattr__(self, name):
                raise AssertionError(f"unexpected native call: {name}")

        client = Client.__new__(Client)
        client.lib = FakeLib()
        client.h = 37
        cases = [
            lambda path: client.stat(path),
            lambda path: client.lstat(path),
            lambda path: client.access(path, os.R_OK),
            lambda path: client.open(path, "wb"),
            lambda path: client.listdir(path),
            lambda path: client.listxattr(path),
            lambda path: client.chmod(path, 0o600),
            lambda path: client.chown(path, 1, 1),
            lambda path: client.rename(path, "/safe"),
            lambda path: client.rename("/safe", path),
            lambda path: client.unlink(path),
        ]
        for path in ("/safe\0.suffix", b"/safe\0.suffix"):
            for operation in cases:
                with self.subTest(path=path, operation=operation):
                    with self.assertRaises(ValueError) as caught:
                        operation(path)
                    self.assertIn("NUL", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
