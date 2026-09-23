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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;

final class NativeMetadata {
    /** IOException carrying the native errno, so search skips can disclose it. */
    static final class NativeError extends IOException {
        final int errno;

        NativeError(String operation, String path, int errno) {
            super(operation + " failed (errno " + errno + "): " + path);
            this.errno = errno;
        }
    }

    private final NativeLibrary.Api api;
    private final Runtime runtime;
    private final long handle;

    NativeMetadata(NativeLibrary.Api api, Runtime runtime, long handle) {
        this.api = api;
        this.runtime = runtime;
        this.handle = handle;
    }

    SearchFiles.FileInfo stat(String path) throws IOException {
        Pointer buffer = Memory.allocateDirect(runtime, 130);
        check(api.jfs_stat1(NativeClient.pid(), handle, path, buffer), "stat", path);
        return decode(path, buffer);
    }

    List<SearchFiles.FileInfo> list(String path) throws IOException {
        if (!stat(path).type.equals("directory")) {
            throw new IOException("path is not a directory: " + path);
        }
        List<SearchFiles.FileInfo> entries = new ArrayList<>();
        Pointer buffer = Memory.allocateDirect(runtime, 32 << 10);
        long offset = 0;
        long nextHandle = handle;
        try (Continuation continuation = new Continuation()) {
            while (true) {
                // Native consumes the previous continuation before producing a new one.
                continuation.fd = 0;
                int length = check(api.jfs_listdir(NativeClient.pid(), nextHandle,
                        path, offset, buffer, 32 << 10), "list", path);
                int remaining = buffer.getInt(length);
                continuation.fd = remaining == 0 ? 0 : buffer.getInt(length + 4);
                int position = 0;
                while (position < length) {
                    int nameLength = buffer.getByte(position++) & 255;
                    byte[] name = new byte[nameLength];
                    buffer.get(position, name, 0, nameLength);
                    position += nameLength;
                    int statLength = buffer.getByte(position++) & 255;
                    String child = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(name)).toString();
                    String childPath = path.equals("/") ? "/" + child : path + "/" + child;
                    entries.add(decode(childPath, buffer.slice(position)));
                    position += statLength;
                }
                if (remaining == 0) {
                    return entries;
                }
                offset = entries.size();
                nextHandle = continuation.fd;
            }
        }
    }

    private static SearchFiles.FileInfo decode(String path, Pointer stat) {
        int mode = stat.getInt(0);
        String type = (mode & (1 << 31)) != 0 ? "directory"
                : (mode & 0x8f280000) == 0 ? "file" : "other";
        return new SearchFiles.FileInfo(path, type, stat.getLongLong(4), stat.getLongLong(12));
    }

    private static int check(int result, String operation, String path) throws IOException {
        if (result < 0) {
            throw new NativeError(operation, path, -result);
        }
        return result;
    }

    private final class Continuation implements AutoCloseable {
        private int fd;

        @Override
        public void close() throws IOException {
            if (fd != 0) {
                check(api.jfs_close(NativeClient.pid(), fd), "close directory", "");
            }
        }
    }
}
