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
"""Run seed/exchange in separate processes using standalone Python AgentFS."""
import os
import sys
import ctypes

from agentfs import AgentFS, Client
from agentfs._native import thread_id


def check(fs, path, text, prefix):
    assert fs.read_file(path)["data"] == text.encode("utf-8")
    page = fs.search("aa", path.rsplit("/", 1)[0])
    assert page["complete"] and not page["skipped"] and page["next_cursor"] is None
    assert [match["offset"] for match in page["matches"]] == [len(prefix.encode("utf-8"))]
    assert [match["path"] for match in page["matches"]] == [path]


def main():
    root = os.environ["AGENTFS_EXCHANGE_ROOT"]
    path = root + "/data"
    client = Client(os.environ["AGENTFS_VOLUME"], os.environ["AGENTFS_META"],
                    cache_dir="memory", no_usage_report=True)
    try:
        fs = AgentFS(client)
        if sys.argv[1] == "seed":
            client.lib.jfs_mkdir(thread_id(), ctypes.c_int64(client.h), root.encode(),
                                 ctypes.c_uint16(0o777), ctypes.c_uint16(client.umask))
            fs.write_file(path, "python 猫 aa\n".encode("utf-8"))
            check(fs, path, "python 猫 aa\n", "python 猫 ")
        elif sys.argv[1] == "exchange":
            check(fs, path, "java 狗 aa\n", "java 狗 ")
            assert fs.read_file(root + "/java-written")["data"] == b"java created\n"
            fs.write_file(path, "python 猫 aa\n".encode("utf-8"))
            fs.edit_file(path, "python", "finished")
            fs.apply_patch(path, "*** Begin Patch\n*** Update File: " + path
                           + "\n@@\n-finished 猫 aa\n+finished 犬 aa\n*** End Patch\n")
            check(fs, path, "finished 犬 aa\n", "finished 犬 ")
        else:
            raise ValueError("Expected seed or exchange")
        print("Cross-language Python " + sys.argv[1] + ": passed")
    finally:
        client.close(terminate=True)


if __name__ == "__main__":
    main()
