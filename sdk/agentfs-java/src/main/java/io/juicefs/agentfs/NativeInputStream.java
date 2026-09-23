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
import java.util.Objects;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;

/** Owns one native descriptor; native pread returning zero means EOF. */
final class NativeInputStream extends InputStream {
    private final NativeLibrary.Api api;
    private final Pointer buffer;
    private final String path;
    private int fd;
    private long position;

    NativeInputStream(NativeLibrary.Api api, Runtime runtime, int fd, long offset, String path) {
        this.api = api;
        this.fd = fd;
        this.position = offset;
        this.path = path;
        this.buffer = Memory.allocateDirect(runtime, 1 << 20);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, data.length);
        if (fd == 0) {
            throw new IOException("File stream is closed");
        }
        if (length == 0) {
            return 0;
        }
        int count = NativeClient.check(api.jfs_pread(NativeClient.pid(), fd, buffer,
                Math.min(length, 1 << 20), position), "read", path);
        if (count == 0) {
            return -1;
        }
        buffer.get(0, data, offset, count);
        position += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        if (fd != 0) {
            int closing = fd;
            fd = 0;
            NativeClient.check(api.jfs_close(NativeClient.pid(), closing), "close file", path);
        }
    }
}
