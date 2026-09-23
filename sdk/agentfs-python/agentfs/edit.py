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

"""Strict single-file editing over the existing Client storage boundary."""

from os import R_OK, W_OK, X_OK
from posixpath import dirname, join, normpath
from stat import S_IMODE, S_ISREG, S_ISVTX
from uuid import uuid4

from .edit_match import output_chunks, read_exact, unique


class EditError(Exception):
    """An edit failure; unchanged describes this call, not other writers."""

    def __init__(self, code, stage="validate", cause=None, outcome="unchanged"):
        super().__init__("{} during {} ({})".format(code, stage, outcome))
        self.code, self.stage, self.cause, self.outcome = code, stage, cause, outcome
        self.temp_path = None
        self.cleanup_error = None


def text_bytes(value):
    if not isinstance(value, str):
        raise EditError("invalid_input")
    try:
        return value.encode("utf-8", "strict")
    except UnicodeError:
        raise EditError("invalid_input") from None


def normalize_path(path):
    text_bytes(path)
    if not path.startswith("/") or "\0" in path:
        raise EditError("invalid_input")
    return normpath(path)


def _ordinary(client, path):
    info = client.lstat(path)
    if not S_ISREG(info.st_mode) or info.st_nlink != 1 or client.listxattr(path):
        raise EditError("unsupported", "object")
    return info


def _same_output(client, path, spans, size, output_size):
    if size != output_size:
        return False
    with client.open(path, "rb") as source, client.open(path, "rb") as original:
        for data in output_chunks(source, spans, size):
            if data != read_exact(original, len(data)):
                return False
    return True


def _write_all(stream, data):
    offset = 0
    while offset < len(data):
        count = stream.write(data[offset:])
        if not count:
            raise OSError("write made no progress")
        offset += count


def _preserve(client, path, original):
    current = _ordinary(client, path)
    if (current.st_uid, current.st_gid) != (original.st_uid, original.st_gid):
        client.chown(path, original.st_uid, original.st_gid)
    client.chmod(path, S_IMODE(original.st_mode))
    current = _ordinary(client, path)
    if (current.st_uid, current.st_gid, S_IMODE(current.st_mode)) != (
            original.st_uid, original.st_gid, S_IMODE(original.st_mode)):
        raise OSError("temporary file attributes were not preserved")


def _commit(client, path, spans, info):
    temporary = join(dirname(path), ".agentfs-edit-{}.tmp".format(uuid4().hex))
    stage, created = "create", False
    try:
        with client.open(temporary, "xb") as output:
            created = True
            stage = "attributes"
            client.chmod(temporary, 0o600)
            temporary_info = _ordinary(client, temporary)
            stage = "access"
            parent = client.stat(dirname(path))
            if parent.st_mode & S_ISVTX and temporary_info.st_uid not in (0, parent.st_uid, info.st_uid):
                raise PermissionError("sticky directory does not permit replacement")
            stage = "write"
            with client.open(path, "rb") as source:
                for data in output_chunks(source, spans, info.st_size):
                    _write_all(output, data)
            stage = "close"
        stage = "attributes"
        _preserve(client, temporary, info)
        stage = "commit"
        client.rename(temporary, path)
    except Exception as cause:
        if stage == "commit":
            error = EditError("commit_unknown", stage, cause, "unknown")
        elif isinstance(cause, EditError):
            error = cause
            error.stage = stage
        else:
            error = EditError("storage_error", stage, cause)
        if created:
            error.temp_path = temporary
            if stage != "commit":
                try:
                    client.unlink(temporary)
                except Exception as cleanup:
                    error.cleanup_error = cleanup
        raise error from None


def _run(client, path, locate):
    stage = "object"
    try:
        info = _ordinary(client, path)
        stage = "access"
        client.access(path, R_OK | W_OK)
        client.access(dirname(path), W_OK | X_OK)
        stage = "match"
        with client.open(path, "rb") as source:
            spans = locate(source, info.st_size)
        output_size = info.st_size + sum(len(data) - (end - start)
                                         for start, end, data in spans)
        if _same_output(client, path, spans, info.st_size, output_size):
            return {"path": path, "replacements": 0, "bytes_written": 0}
        _commit(client, path, spans, info)
        return {"path": path, "replacements": len(spans), "bytes_written": output_size}
    except EditError:
        raise
    except ValueError as cause:
        if str(cause) in ("no_match", "ambiguous_match"):
            raise EditError(str(cause), stage, cause) from None
        raise EditError("storage_error", stage, cause) from None
    except Exception as cause:
        raise EditError("storage_error", stage, cause) from None


def edit_file(client, path, old_text, new_text):
    path = normalize_path(path)
    old, new = text_bytes(old_text), text_bytes(new_text)
    if not old:
        raise EditError("invalid_input")

    def locate(source, size):
        start = unique(source, old, end=size)
        return [(start, start + len(old), new)]

    return _run(client, path, locate)


def apply_patch(client, path, patch):
    from .edit_patch import locate, parse
    path = normalize_path(path)
    blocks = parse(path, text_bytes(patch))
    return _run(client, path, lambda stream, size: locate(stream, size, blocks))
