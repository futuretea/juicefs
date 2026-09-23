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
import java.util.Arrays;
import java.util.List;
import jnr.ffi.Runtime;

final class NativeClient implements AutoCloseable, SearchFiles {
    private final NativeLibrary.Api api;
    private final Runtime runtime;
    private final String user;
    private final String groups;
    private long handle;

    NativeClient(String volume, String metadataUrl, String user, String groups,
                 String superuser, String supergroup) throws IOException {
        api = NativeLibrary.load();
        runtime = Runtime.getRuntime(api);
        this.user = user;
        this.groups = groups;
        handle = api.jfs_init(null, 0, volume, NativeConfig.json(metadataUrl),
                user, groups, superuser, supergroup);
        if (handle <= 0) {
            throw new IOException("JuiceFS initialization failed");
        }
    }

    NativeClient(NativeLibrary.Api api, Runtime runtime, long handle) {
        this.api = api;
        this.runtime = runtime;
        this.handle = handle;
        this.user = "";
        this.groups = "";
    }

    AgentFileEdit.Storage editStorage() throws IOException {
        requireOpen();
        return new NativeEditStorage(this, api, runtime, handle, user, groups);
    }

    AgentFS.ReadResult readFile(String path, long offset, int limit) throws IOException {
        requireOpen();
        path = Inputs.path(path);
        if (offset < 0 || limit <= 0 || limit == Integer.MAX_VALUE
                || offset > Long.MAX_VALUE - limit) {
            throw new IllegalArgumentException("Invalid read offset or limit");
        }
        try (InputStream input = open(path, offset)) {
            byte[] data = new byte[limit + 1];
            int size = 0;
            while (size < data.length) {
                int read = input.read(data, size, data.length - size);
                if (read < 0) {
                    break;
                }
                size += read;
            }
            return new AgentFS.ReadResult(path, Arrays.copyOf(data, Math.min(size, limit)),
                    offset, size > limit);
        }
    }

    AgentFS.WriteResult writeFile(String path, byte[] data) throws IOException {
        requireOpen();
        path = Inputs.path(path);
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        int fd = api.jfs_create(pid(), handle, path, (short) 0666, (short) 0022);
        if (fd == -2) {
            String parent = path.substring(0, path.lastIndexOf('/') + 1);
            check(api.jfs_mkdirAll(pid(), handle, parent, (short) 0777, (short) 0022, true),
                    "mkdir", parent);
            fd = api.jfs_create(pid(), handle, path, (short) 0666, (short) 0022);
        }
        if (fd == -17) {
            check(api.jfs_unlink(pid(), handle, path), "unlink", path);
            fd = api.jfs_create(pid(), handle, path, (short) 0666, (short) 0022);
        }
        try (OutputStream output = new NativeOutputStream(api, runtime, check(fd, "create", path), path)) {
            output.write(data);
        }
        return new AgentFS.WriteResult(path, data.length);
    }

    void requireOpen() throws IOException {
        if (handle == 0) {
            throw new IOException("AgentFS client is closed");
        }
    }

    @Override
    public SearchFiles.FileInfo stat(String path) throws IOException {
        requireOpen();
        return new NativeMetadata(api, runtime, handle).stat(Inputs.path(path));
    }

    @Override
    public List<SearchFiles.FileInfo> list(String path) throws IOException {
        requireOpen();
        return new NativeMetadata(api, runtime, handle).list(Inputs.path(path));
    }

    @Override
    public InputStream open(String path, long offset) throws IOException {
        requireOpen();
        path = Inputs.path(path);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be nonnegative");
        }
        int fd = check(api.jfs_open(pid(), handle, path, null, 4), "open", path);
        return new NativeInputStream(api, runtime, fd, offset, path);
    }

    static long pid() {
        return Thread.currentThread().getId();
    }

    static int check(int result, String operation, String path) throws IOException {
        if (result < 0) {
            throw new IOException(operation + " failed (errno " + -result + "): " + path);
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        if (handle != 0) {
            long closing = handle;
            handle = 0;
            check(api.jfs_term(pid(), closing, 1), "close client", "");
        }
    }

}
