/*
 * JuiceFS, Copyright 2026 Juicedata, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.juicefs.agentfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.Struct;

/** Native permissions and replacement operations used only by the editor. */
final class NativeEditStorage implements AgentFileEdit.Storage {
    private final NativeClient client;
    private final NativeLibrary.Api api;
    private final Runtime runtime;
    private final long handle;
    private final String user;
    private final String groups;

    NativeEditStorage(NativeClient client, NativeLibrary.Api api, Runtime runtime,
                      long handle, String user, String groups) {
        this.client = client;
        this.api = api;
        this.runtime = runtime;
        this.handle = handle;
        this.user = user;
        this.groups = groups;
    }

    @Override
    public EditFileInfo stat(String path) throws IOException {
        client.requireOpen();
        EditFileInfo.Native info = new EditFileInfo.Native(runtime);
        check(api.jfs_lstat(NativeClient.pid(), handle, path, Struct.getMemory(info)), "lstat", path);
        Pointer names = Memory.allocateDirect(runtime, 130);
        int length = check(api.jfs_lstat1(NativeClient.pid(), handle, path, names), "lstat names", path);
        byte[] encoded = new byte[length - 28];
        names.get(28, encoded, 0, encoded.length);
        String[] ownerGroup = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded))
                .toString().split("\u0000", -1);
        return new EditFileInfo(info.inode.get(), info.nlink.get(), info.uid.get(), info.gid.get(),
                (int) info.mode.get(), info.length.get(), ownerGroup[0], ownerGroup[1]);
    }

    @Override
    public List<String> xattrs(String path) throws IOException {
        client.requireOpen();
        // The editor needs only presence, not attribute names or values.
        int size = check(api.jfs_listXattr(NativeClient.pid(), handle, path,
                Memory.allocateDirect(runtime, 1), 1), "list attributes", path);
        return size == 0 ? List.of() : List.of("present");
    }

    @Override
    public void access(String path) throws IOException {
        client.requireOpen();
        String parent = path.substring(0, path.lastIndexOf('/') + 1);
        EditFileInfo directory = stat(parent);
        if ((directory.mode & 01000) != 0
                && check(api.jfs_is_superuser(handle, user, groups), "check identity", path) == 0
                && !user.equals(directory.owner) && !user.equals(stat(path).owner)) {
            throw new IOException("edit requires ownership in a sticky directory: " + path);
        }
        check(api.jfs_access(NativeClient.pid(), handle, path, 6), "edit access", path);
        check(api.jfs_access(NativeClient.pid(), handle, parent, 3), "edit parent access", parent);
    }

    @Override
    public InputStream open(String path) throws IOException {
        return client.open(path, 0);
    }

    @Override
    public OutputStream create(String path) throws IOException {
        client.requireOpen();
        int fd = check(api.jfs_create(NativeClient.pid(), handle, path, (short) 0600, (short) 0),
                "create edit temporary", path);
        return new NativeOutputStream(api, runtime, fd, path);
    }

    @Override
    public void preserve(String path, EditFileInfo original) throws IOException {
        EditFileInfo staged = stat(path);
        if (staged.uid != original.uid || staged.gid != original.gid) {
            check(api.jfs_setOwner(NativeClient.pid(), handle, path, original.owner, original.group),
                    "preserve owner", path);
        }
        check(api.jfs_chmod(NativeClient.pid(), handle, path, original.mode & 07777), "preserve mode", path);
    }

    @Override
    public void commit(String source, String target) throws IOException {
        try {
            access(target);
        } catch (IOException | RuntimeException error) {
            throw new EditException("storage_error", "unchanged", "access", error);
        }
        check(api.jfs_rename0(NativeClient.pid(), handle, source, target, 0), "commit", target);
    }

    @Override
    public void delete(String path) throws IOException {
        client.requireOpen();
        check(api.jfs_unlink(NativeClient.pid(), handle, path), "remove edit temporary", path);
    }

    private static int check(int result, String operation, String path) throws IOException {
        return NativeClient.check(result, operation, path);
    }
}
