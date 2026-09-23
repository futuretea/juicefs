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

"""The package-owned ctypes boundary for the JuiceFS native library."""

import ctypes
import os
import sys
import threading


class FileInfo(ctypes.Structure):
    _fields_ = [
        ("inode", ctypes.c_uint64),
        ("mode", ctypes.c_uint32),
        ("uid", ctypes.c_uint32),
        ("gid", ctypes.c_uint32),
        ("atime", ctypes.c_uint32),
        ("mtime", ctypes.c_uint32),
        ("ctime", ctypes.c_uint32),
        ("nlink", ctypes.c_uint32),
        ("length", ctypes.c_uint64),
    ]


def thread_id():
    return ctypes.c_int64(threading.get_ident())


def check_error(result, function, _arguments):
    if function.__name__ == "jfs_init" and result == 0:
        raise OSError(1, "JuiceFS initialization failed")
    if result < 0:
        error = -result
        raise OSError(error, f"{function.__name__}: {os.strerror(error)}")
    return result


class NativeLibrary:
    def __init__(self):
        extension = "dll" if sys.platform == "win32" else "dylib" if sys.platform == "darwin" else "so"
        self.lib = ctypes.CDLL(os.path.join(os.path.dirname(__file__), f"libjfs.{extension}"))

    def __getattr__(self, name):
        function = getattr(self.lib, name)
        if name.startswith("jfs_"):
            function.restype = ctypes.c_int64 if name in ("jfs_init", "jfs_lseek") else ctypes.c_int32
            function.errcheck = check_error
        elif name == "free":
            function.argtypes = [ctypes.c_void_p]
            function.restype = None
        return function
