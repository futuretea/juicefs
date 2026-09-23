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

"""Minimal package-owned JuiceFS connection used by AgentFS."""

import ctypes
import errno
import grp
import json
import os
import pwd
import struct

from ._native import FileInfo, NativeLibrary, thread_id
from ._stream import Stream


MODE_WRITE = 2
MODE_READ = 4
_ENTRY = struct.Struct("!IQIIIQIII")


def _stat(info):
    return os.stat_result((info.mode, info.inode, 0, info.nlink, info.uid,
                           info.gid, info.length, info.atime, info.mtime, info.ctime))


def _encode_path(path):
    encoded = os.fsencode(path)
    if b"\0" in encoded:
        raise ValueError("path must not contain NUL")
    return encoded


class Client:
    """A connection to a JuiceFS volume, owned and closed by the caller."""

    def __init__(self, name, meta, *, bucket="", storage_class="", read_only=False,
                 no_session=False, no_bgjob=True, open_cache="0", backup_meta="3600",
                 backup_skip_trash=False, heartbeat="12", cache_dir="memory", cache_size="100M",
                 free_space_ratio="0.1", cache_partial_only=False, verify_cache_checksum="extend",
                 cache_eviction="2-random", cache_scan_interval="3600", cache_expire="0",
                 writeback=False, buffer_size="300M", prefetch=1, max_readahead="0",
                 upload_limit="0", download_limit="0", max_uploads=20, max_deletes=10,
                 skip_dir_nlink=20, skip_dir_mtime="100ms", io_retries=10, get_timeout="5",
                 put_timeout="60", fast_resolve=False, attr_cache="1s", entry_cache="0s",
                 dir_entry_cache="1s", debug=False, no_usage_report=False, access_log="",
                 push_gateway="", push_interval="10", push_auth="", push_labels="",
                 push_graphite="", push_remote_write="", push_remote_write_auth=""):
        self.h = 0
        self.lib = NativeLibrary()
        config = {
            "meta": meta, "bucket": bucket, "storageClass": storage_class,
            "readOnly": read_only, "noSession": no_session, "noBGJob": no_bgjob,
            "openCache": open_cache, "backupMeta": backup_meta,
            "backupSkipTrash": backup_skip_trash, "heartbeat": heartbeat,
            "cacheDir": cache_dir, "cacheSize": cache_size, "freeSpace": free_space_ratio,
            "autoCreate": True, "cacheFullBlock": not cache_partial_only,
            "cacheChecksum": verify_cache_checksum, "cacheEviction": cache_eviction,
            "cacheScanInterval": cache_scan_interval, "cacheExpire": cache_expire,
            "writeback": writeback, "memorySize": buffer_size, "prefetch": prefetch,
            "readahead": max_readahead, "uploadLimit": upload_limit,
            "downloadLimit": download_limit, "maxUploads": max_uploads,
            "maxDeletes": max_deletes, "skipDirNlink": skip_dir_nlink,
            "skipDirMtime": skip_dir_mtime, "ioRetries": io_retries,
            "getTimeout": get_timeout, "putTimeout": put_timeout,
            "fastResolve": fast_resolve, "attrTimeout": attr_cache,
            "entryTimeout": entry_cache, "dirEntryTimeout": dir_entry_cache,
            "debug": debug, "noUsageReport": no_usage_report,
            "accessLog": access_log, "pushGateway": push_gateway,
            "pushInterval": push_interval, "pushAuth": push_auth,
            "pushLabels": push_labels, "pushGraphite": push_graphite,
            "pushRemoteWrite": push_remote_write,
            "pushRemoteWriteAuth": push_remote_write_auth, "caller": 1,
        }
        self.umask = os.umask(0)
        os.umask(self.umask)
        user = pwd.getpwuid(os.geteuid())
        groups = [grp.getgrgid(gid).gr_name for gid in os.getgrouplist(user.pw_name, user.pw_gid)]
        superuser = pwd.getpwuid(0)
        supergroups = [grp.getgrgid(gid).gr_name for gid in
                       os.getgrouplist(superuser.pw_name, superuser.pw_gid)]
        self.h = self.lib.jfs_init(
            0, 0, os.fsencode(name), json.dumps(config, sort_keys=True).encode(),
            user.pw_name.encode(), ",".join(groups).encode(),
            superuser.pw_name.encode(), ",".join(supergroups).encode())

    def __del__(self):
        if getattr(self, "h", 0):
            try:
                self.close()
            except Exception:
                pass

    def _handle(self):
        if not getattr(self, "h", 0):
            raise ValueError("I/O operation on closed Client")
        return ctypes.c_int64(self.h)

    def close(self, terminate=False):
        """Flush this handle; terminate the cached filesystem only when requested."""
        if getattr(self, "h", 0):
            handle = self.h
            self.lib.jfs_term(thread_id(), ctypes.c_int64(handle), 1 if terminate else 0)
            self.h = 0

    def stat(self, path):
        handle = self._handle()
        path = _encode_path(path)
        info = FileInfo()
        self.lib.jfs_stat(thread_id(), handle, path, ctypes.byref(info))
        return _stat(info)

    def lstat(self, path):
        handle = self._handle()
        path = _encode_path(path)
        info = FileInfo()
        self.lib.jfs_lstat(thread_id(), handle, path, ctypes.byref(info))
        return _stat(info)

    def access(self, path, mode):
        handle = self._handle()
        path = _encode_path(path)
        self.lib.jfs_access(thread_id(), handle, path, ctypes.c_int64(mode))

    def open(self, path, mode="rb"):
        if mode not in ("rb", "wb", "xb"):
            raise ValueError(f"unsupported mode: {mode}")
        handle = self._handle()
        encoded = _encode_path(path)
        length = ctypes.c_uint64()
        if mode == "xb":
            fd = self.lib.jfs_create(thread_id(), handle, encoded,
                                     ctypes.c_uint16(0o666), ctypes.c_uint16(self.umask))
        else:
            flag = MODE_READ if mode == "rb" else MODE_WRITE
            try:
                fd = self.lib.jfs_open_posix(thread_id(), handle, encoded,
                                             ctypes.byref(length), ctypes.c_int32(flag))
            except OSError as error:
                if mode != "wb" or error.errno != errno.ENOENT:
                    raise
                fd = self.lib.jfs_create(thread_id(), handle, encoded,
                                         ctypes.c_uint16(0o666), ctypes.c_uint16(self.umask))
            else:
                if mode == "wb":
                    try:
                        self.lib.jfs_ftruncate(thread_id(), ctypes.c_int32(fd), ctypes.c_uint64(0))
                    except Exception:
                        self.lib.jfs_close(thread_id(), ctypes.c_int32(fd))
                        raise
        return Stream(self, fd, mode, length.value if mode == "rb" else 0)

    def listdir(self, path, detail=True):
        handle = self._handle()
        path = _encode_path(path)
        buffer = ctypes.c_void_p()
        size = ctypes.c_int64()
        try:
            self.lib.jfs_listdir2(thread_id(), handle, path,
                                  bool(detail), ctypes.byref(buffer), ctypes.byref(size))
            data = ctypes.string_at(buffer, size.value)
            entries = []
            offset = 0
            while offset < len(data):
                name_length, = struct.unpack_from("!H", data, offset)
                offset += 2
                name = data[offset:offset + name_length].decode(errors="replace")
                offset += name_length
                if detail:
                    mode, inode, nlink, uid, gid, length, atime, mtime, ctime = _ENTRY.unpack_from(data, offset)
                    offset += _ENTRY.size
                    info = os.stat_result((mode, inode, 0, nlink, uid, gid,
                                           length, atime, mtime, ctime))
                    entries.append((name, info))
                else:
                    entries.append(name)
            return sorted(entries)
        finally:
            if buffer.value:
                self.lib.free(buffer)

    def listxattr(self, path):
        handle = self._handle()
        path = _encode_path(path)
        buffer = ctypes.c_void_p()
        size = ctypes.c_int64()
        try:
            self.lib.jfs_listXattr2(thread_id(), handle, path,
                                    ctypes.byref(buffer), ctypes.byref(size))
            data = ctypes.string_at(buffer, size.value).decode()
            return data.split("\0")[:-1] if data else []
        finally:
            if buffer.value:
                self.lib.free(buffer)

    def chmod(self, path, mode):
        handle = self._handle()
        path = _encode_path(path)
        self.lib.jfs_chmod(thread_id(), handle, path, ctypes.c_uint16(mode))

    def chown(self, path, uid, gid):
        handle = self._handle()
        path = _encode_path(path)
        self.lib.jfs_chown(thread_id(), handle, path,
                           ctypes.c_uint32(uid), ctypes.c_uint32(gid))

    def rename(self, old, new):
        handle = self._handle()
        old_path = _encode_path(old)
        new_path = _encode_path(new)
        self.lib.jfs_rename0(thread_id(), handle, old_path,
                             new_path, ctypes.c_uint32(0))

    def unlink(self, path):
        handle = self._handle()
        path = _encode_path(path)
        self.lib.jfs_unlink(thread_id(), handle, path)
