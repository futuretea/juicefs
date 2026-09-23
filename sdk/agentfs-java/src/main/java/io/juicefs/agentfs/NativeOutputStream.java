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
import java.io.OutputStream;
import java.util.Objects;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;

/** Closes explicitly so commit cannot hide a writeback error. */
final class NativeOutputStream extends OutputStream {
    private final NativeLibrary.Api api;
    private final Pointer buffer;
    private final String path;
    private int fd;

    NativeOutputStream(NativeLibrary.Api api, Runtime runtime, int fd, String path) {
        this.api = api;
        this.fd = fd;
        this.path = path;
        this.buffer = Memory.allocateDirect(runtime, 1 << 20);
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[] {(byte) value});
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, data.length);
        if (fd == 0) {
            throw new IOException("File stream is closed");
        }
        while (length > 0) {
            int count = Math.min(length, 1 << 20);
            buffer.put(0, data, offset, count);
            int written = NativeClient.check(api.jfs_write(NativeClient.pid(), fd, buffer, count), "write", path);
            if (written == 0) {
                throw new IOException("write made no progress: " + path);
            }
            offset += written;
            length -= written;
        }
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
