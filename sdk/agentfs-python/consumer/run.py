#!/usr/bin/env python3
"""Exercise the installed AgentFS wheel against a fresh isolated volume."""

import base64
import ctypes
import importlib.metadata
import json
import os
import struct
import sys
from pathlib import Path

import agentfs
from agentfs import (AgentFS, Client, RawSearchProvider, RipgrepSearchProvider,
                     SearchProvider, SearchProviderError)
from agentfs._native import thread_id
from agentfs.edit import EditError
from agentfs.search import READ_SIZE


VOLUME = os.environ["AGENTFS_VOLUME"]
META = os.environ["AGENTFS_META"]
PATCH = "*** Begin Patch\n*** Update File: {}\n@@\n-cat\n+dog\n*** End Patch\n"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def connect():
    return Client(VOLUME, META, cache_dir="memory", no_usage_report=True)


def native(client, symbol, *args):
    return getattr(client.lib, symbol)(thread_id(), ctypes.c_int64(client.h), *args)


def file_state(client, path):
    info = client.lstat(path)
    return info.st_ino, info.st_mode, info.st_uid, info.st_gid, info.st_nlink


def read(fs, path):
    return fs.read_file(path, limit=2 << 20)["data"]


def edit(fs, operation, path):
    if operation == "replace":
        return fs.edit_file(path, "cat", "dog")
    return fs.apply_patch(path, PATCH.format(path))


def special_objects(client, fs):
    fs.write_file("/link-source", b"cat\n")
    fs.write_file("/xattr", b"cat\n")
    fs.write_file("/acl", b"cat\n")
    native(client, "jfs_symlink", b"/link-source", b"/symlink")
    native(client, "jfs_link", b"/link-source", b"/hardlink")
    native(client, "jfs_setXattr", b"/xattr", b"user.synthetic", b"fixture",
           ctypes.c_int32(7), ctypes.c_int32(0))
    acl_name = b"nobody"
    acl = struct.pack("=6H", 7, 5, 5, 7, 1, 0) + bytes([len(acl_name)]) + acl_name + b"\x04"
    acl_buffer = ctypes.create_string_buffer(acl)
    native(client, "jfs_setfacl", b"/acl", ctypes.c_int32(1),
           ctypes.cast(acl_buffer, ctypes.c_void_p), ctypes.c_int32(len(acl)))
    require(client.lstat("/hardlink").st_nlink == 2, "hard-link fixture was not created")
    require(client.listxattr("/xattr"), "xattr fixture was not created")
    require(client.listxattr("/acl"), "ACL fixture was not created")

    for path in ("/symlink", "/hardlink", "/xattr", "/acl"):
        for operation in ("replace", "patch"):
            before, original = file_state(client, path), read(fs, path)
            try:
                edit(fs, operation, path)
            except EditError as error:
                require(error.code == "unsupported" and error.outcome == "unchanged",
                        f"wrong refusal for {path}: {error}")
            else:
                raise AssertionError(f"unsupported edit succeeded: {path}")
            require(file_state(client, path) == before and read(fs, path) == original,
                    f"rejected edit changed {path}")


def changed_scan(client, fs):
    fs.write_file("/mtime", b"target")

    class ChangingClient:
        def __getattr__(self, name):
            return getattr(client, name)

        def open(self, path, mode):
            stream = client.open(path, mode)
            if path == "/mtime" and mode == "rb":
                info = client.stat(path)
                with client.open(path, "wb") as changed:
                    changed.write(b"xarget")
                native(client, "jfs_utime", b"/mtime",
                       ctypes.c_int64((int(info.st_mtime) + 5) * 1000),
                       ctypes.c_int64(int(info.st_atime) * 1000))
            return stream

    result = AgentFS(ChangingClient()).search("target")
    require(not result["complete"] and not result["matches"], "mtime scan claimed full coverage")
    require({"path": "/mtime", "reason": "changed"} in result["skipped"],
            "same-size mtime change was not disclosed")


def shared_edit_cases(fs):
    fixture = json.loads(Path(__file__).with_name("edit_cases.json").read_text())
    for case in fixture["cases"]:
        path = "/a"
        original = base64.b64decode(case["input_b64"])
        fs.write_file(path, original)
        try:
            if case["operation"] == "edit":
                result = fs.edit_file(path, case["old_text"], case["new_text"])
            else:
                result = fs.apply_patch(path, case["patch"])
        except EditError as error:
            expected = case.get("error")
            require(expected is not None and error.code == expected["code"] and
                    error.outcome == expected["outcome"],
                    f"wrong shared edit error for {case['id']}: {error}")
            require(read(fs, path) == original,
                    f"failed shared edit changed bytes for {case['id']}")
        else:
            require("error" not in case and result["replacements"] == case["replacements"]
                    and read(fs, path) == base64.b64decode(case["expected_b64"]),
                    f"wrong shared edit result for {case['id']}")


def search_boundaries(client, fs):
    native(client, "jfs_mkdir", b"/search-fixtures", ctypes.c_uint16(0o755),
           ctypes.c_uint16(client.umask))
    fs.write_file("/search-fixtures/overlap", b"aaaa")
    fs.write_file("/search-fixtures/cross", b"x" * (READ_SIZE - 1) + b"needle")
    for provider in (RawSearchProvider(), RipgrepSearchProvider()):
        search = AgentFS(client, search_provider=provider)
        overlap = search.search("aa", root="/search-fixtures", limit=2)
        require([item["offset"] for item in overlap["matches"]] == [0, 1]
                and overlap["next_cursor"], "overlap first page differs")
        continued = search.search("aa", root="/search-fixtures", limit=2,
                                  cursor=overlap["next_cursor"])
        require([item["offset"] for item in continued["matches"]] == [2]
                and continued["complete"], "overlap continuation differs")
        cross = search.search("needle", root="/search-fixtures")
        require(cross["matches"] == [{"path": "/search-fixtures/cross",
                                      "offset": READ_SIZE - 1, "length": 6}]
                and cross["complete"], "cross-block search differs")


def nul_path_boundaries(client, fs):
    fs.write_file("/nul-probe", b"untouched")
    invalid = "/nul-probe\0.suffix"
    cases = [
        lambda: fs.read_file(invalid),
        lambda: fs.write_file(invalid, b"unexpected"),
        lambda: fs.list_directory(invalid),
        lambda: fs.search("needle", root=invalid),
        lambda: client.open(invalid, "wb"),
        lambda: client.rename("/nul-probe", invalid),
        lambda: client.rename(invalid, "/nul-probe"),
    ]
    for operation in cases:
        try:
            operation()
        except ValueError as error:
            require("NUL" in str(error), f"unclear NUL rejection: {error}")
        else:
            raise AssertionError("embedded-NUL path was accepted")
    require(read(fs, "/nul-probe") == b"untouched",
            "embedded-NUL path changed the native prefix file")


def unknown_commit(client, fs):
    class AmbiguousClient:
        renames = 0

        def __getattr__(self, name):
            return getattr(client, name)

        def rename(self, source, target):
            self.renames += 1
            client.rename(source, target)
            raise OSError("rename response was lost")

    for path, operation in (("/unknown-replace", "replace"),
                            ("/unknown-patch", "patch")):
        fs.write_file(path, b"cat\n")
        ambiguous = AmbiguousClient()
        try:
            edit(AgentFS(ambiguous), operation, path)
        except EditError as error:
            require(error.code == "commit_unknown" and error.outcome == "unknown",
                    f"wrong ambiguous-commit result for {operation}: {error}")
            require(error.stage == "commit", "ambiguous commit stage was lost")
        else:
            raise AssertionError(f"ambiguous {operation} commit was reported as success")
        require(ambiguous.renames == 1, "ambiguous commit was retried")
        require(read(fs, path) == b"dog\n", "committed bytes were not preserved")


def exercise():
    require(importlib.metadata.version("agentfs") == "0.1.0", "wrong installed distribution")
    require(not any(distribution.metadata["Name"].lower() == "juicefs"
                    for distribution in importlib.metadata.distributions()),
            "old juicefs distribution is installed")
    require("site-packages" in agentfs.__file__, "AgentFS was not imported from installation")
    require(issubclass(RawSearchProvider, SearchProvider), "Raw provider is not public")
    require(issubclass(RipgrepSearchProvider, SearchProvider), "Ripgrep provider is not public")
    require(issubclass(SearchProviderError, RuntimeError), "provider error is not public")

    client = connect()
    try:
        fs = AgentFS(client)
        require("site-packages" in client.lib.lib._name, "native library was not loaded from wheel")
        fs.write_file("/a.txt", b"prefix needle\n")
        fs.write_file("/b.txt", b"needle suffix\n")
        require(fs.read_file("/a.txt", 7, 6)["data"] == b"needle", "byte-range read failed")
        first = fs.list_directory("/", limit=1)
        second = fs.list_directory("/", offset=first["next_offset"], limit=1)
        require([first["entries"][0]["name"], second["entries"][0]["name"]]
                == ["a.txt", "b.txt"], "directory pagination failed")

        nul_path_boundaries(client, fs)

        for provider in (RawSearchProvider(), RipgrepSearchProvider()):
            search = AgentFS(client, search_provider=provider)
            page = search.search("needle", limit=1)
            require(page["matches"] == [{"path": "/a.txt", "offset": 7, "length": 6}],
                    "first search page differs")
            next_page = search.search("needle", limit=1, cursor=page["next_cursor"])
            require(next_page["matches"] == [{"path": "/b.txt", "offset": 0, "length": 6}],
                    "second search page differs")

        search_boundaries(client, fs)
        shared_edit_cases(fs)

        fs.write_file("/edit-replace", b"cat\n")
        fs.write_file("/edit-patch", b"cat\n")
        for path, operation in (("/edit-replace", "replace"), ("/edit-patch", "patch")):
            before = file_state(client, path)
            edit(fs, operation, path)
            after = file_state(client, path)
            require(read(fs, path) == b"dog\n" and before[1:] == after[1:],
                    f"{operation} changed bytes or attributes incorrectly")

        special_objects(client, fs)
        changed_scan(client, fs)
        unknown_commit(client, fs)
        fs.write_file("/private", b"cat\n")
        client.chmod("/private", 0o600)
    finally:
        client.close()

    try:
        client.stat("/a.txt")
    except ValueError:
        pass
    else:
        raise AssertionError("closed Client remained usable")
    reopened = connect()
    try:
        require(read(AgentFS(reopened), "/a.txt") == b"prefix needle\n",
                "new connection could not reuse the volume")
    finally:
        reopened.close(terminate=True)
    print("standalone AgentFS clean consumer: six operations and refusal boundaries passed")


def permissions():
    require(os.geteuid() != 0, "permission phase must be unprivileged")
    client = connect()
    try:
        fs = AgentFS(client)
        for operation in ("replace", "patch"):
            try:
                edit(fs, operation, "/private")
            except EditError as error:
                require(error.code == "storage_error" and error.outcome == "unchanged",
                        f"wrong permission refusal: {error}")
            else:
                raise AssertionError("unprivileged edit succeeded")
    finally:
        client.close(terminate=True)
    print("standalone AgentFS unprivileged edit refusal passed")


def verify_permissions():
    require(os.geteuid() == 0, "verification phase must be privileged")
    client = connect()
    try:
        fs = AgentFS(client)
        require(read(fs, "/private") == b"cat\n", "denied edit changed private bytes")
        require(client.lstat("/private").st_mode & 0o777 == 0o600,
                "denied edit changed private mode")
    finally:
        client.close(terminate=True)
    print("standalone AgentFS denied edit left target unchanged")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("exercise", "permissions", "verify_permissions"):
        raise SystemExit("Usage: run.py exercise|permissions|verify_permissions")
    {"exercise": exercise, "permissions": permissions,
     "verify_permissions": verify_permissions}[sys.argv[1]]()
