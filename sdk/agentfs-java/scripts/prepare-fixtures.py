"""Create fresh synthetic native-test fixtures using the standalone Python Client."""
import os
import ctypes

from agentfs import Client
from agentfs._native import thread_id


def native(client, symbol, *args):
    return getattr(client.lib, symbol)(thread_id(), ctypes.c_int64(client.h), *args)


def mkdir(client, path, mode=0o777):
    native(client, "jfs_mkdir", path.encode(), ctypes.c_uint16(mode),
           ctypes.c_uint16(client.umask))


def prepare(client):
    root = os.environ["AGENTFS_TEST_ROOT"]
    mkdir(client, root)
    mkdir(client, root + "/private", 0o700)
    with client.open(root + "/private/denied.txt", "wb") as stream:
        stream.write(b"permission fixture must remain unchanged\n")
    client.chmod(root + "/private/denied.txt", 0o600)
    mkdir(client, root + "/empty-directory")


def permissions(client):
    root = os.environ["AGENTFS_PERMISSION_ROOT"]

    def owner(path, user, group):
        native(client, "jfs_setOwner", path.encode(), user.encode(), group.encode())

    def file(path):
        with client.open(path, "wb") as stream:
            stream.write(b"cat\n")

    mkdir(client, root)
    for name in ("allowed", "denied", "parent-denied", "sticky"):
        directory = root + "/" + name
        mkdir(client, directory)
        file(directory + "/data")
    owner(root + "/allowed", "agentfs-editor", "agentfs-editors")
    owner(root + "/allowed/data", "agentfs-editor", "agentfs-editors")
    client.chmod(root + "/allowed", 0o750)
    client.chmod(root + "/allowed/data", 0o640)
    client.chmod(root + "/denied", 0o700)
    client.chmod(root + "/denied/data", 0o600)
    owner(root + "/parent-denied/data", "agentfs-editor", "agentfs-editors")
    client.chmod(root + "/parent-denied/data", 0o640)
    client.chmod(root + "/parent-denied", 0o555)
    owner(root + "/sticky/data", "agentfs-other", "agentfs-others")
    client.chmod(root + "/sticky/data", 0o666)
    client.chmod(root + "/sticky", 0o1777)
    file(root + "/hardlink")
    native(client, "jfs_link", (root + "/hardlink").encode(),
           (root + "/hardlink-alias").encode())
    file(root + "/symlink-target")
    native(client, "jfs_symlink", (root + "/symlink-target").encode(),
           (root + "/symlink").encode())
    file(root + "/xattr")
    value = ctypes.create_string_buffer(b"fixture")
    native(client, "jfs_setXattr", (root + "/xattr").encode(), b"user.agentfs",
           ctypes.cast(value, ctypes.c_void_p), ctypes.c_int32(7), ctypes.c_int32(0))
    assert client.stat(root + "/allowed/data").st_uid != 0
    assert client.stat(root + "/sticky").st_mode & 0o1777 == 0o1777
    assert client.stat(root + "/hardlink").st_nlink == 2


client = Client(os.environ["AGENTFS_VOLUME"], os.environ["AGENTFS_META"],
                cache_dir="memory", no_usage_report=True)
try:
    prepare(client)
    permissions(client)
    print("Native fixtures prepared")
finally:
    client.close(terminate=True)
