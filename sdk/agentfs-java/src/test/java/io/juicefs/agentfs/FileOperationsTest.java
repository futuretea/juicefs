/*
 * JuiceFS, Copyright 2026 Juicedata, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.juicefs.agentfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import jnr.ffi.Pointer;
import org.junit.Test;
import static org.junit.Assert.*;

/** U2/S5: observe short I/O through the public facade with an injected native boundary. */
public class FileOperationsTest {
    @Test
    public void s5ShortReadsFillLimitAndReportNextOffset() throws Exception {
        FakeNative nativeCalls = new FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            AgentFS.ReadResult result = fs.readFile("/data", 2, 4);
            assertArrayEquals(bytes("2345"), result.data);
            assertEquals(2, result.offset);
            assertEquals(6, result.nextOffset);
            assertTrue(result.truncated);
            assertEquals(1, nativeCalls.closes);
        }
    }

    @Test
    public void exactEofAndPastEofAreNotTruncated() throws Exception {
        FakeNative nativeCalls = new FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            AgentFS.ReadResult exact = fs.readFile("/data", 6, 4);
            assertArrayEquals(bytes("6789"), exact.data);
            assertEquals(10, exact.nextOffset);
            assertFalse(exact.truncated);
            AgentFS.ReadResult past = fs.readFile("/data", 20, 4);
            assertArrayEquals(new byte[0], past.data);
            assertEquals(20, past.nextOffset);
            assertFalse(past.truncated);
            nativeCalls.source = new byte[0];
            assertArrayEquals(new byte[0], fs.readFile("/data", 0, 4).data);
        }
    }

    @Test
    public void invalidReadBoundsDoNotOpenFiles() throws Exception {
        FakeNative nativeCalls = new FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            assertThrows(IllegalArgumentException.class, () -> fs.readFile("/data", -1, 4));
            assertThrows(IllegalArgumentException.class, () -> fs.readFile("/data", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> fs.readFile("/data", 0, -1));
            assertThrows(IllegalArgumentException.class, () -> fs.readFile("/data", 0, Integer.MAX_VALUE));
            assertEquals(0, nativeCalls.opens);
        }
    }

    @Test
    public void shortWritesPreserveAllBytes() throws Exception {
        FakeNative nativeCalls = new FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            AgentFS.WriteResult result = fs.writeFile("/data", bytes("abcdefg"));
            assertEquals(7, result.bytesWritten);
            assertArrayEquals(bytes("abcdefg"), nativeCalls.written.toByteArray());
            assertEquals(1, nativeCalls.closes);
        }
    }

    @Test
    public void zeroProgressWriteAndCloseFailureAreNotSuccess() throws Exception {
        FakeNative nativeCalls = new FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            nativeCalls.writeLimit = 0;
            assertThrows(IOException.class, () -> fs.writeFile("/data", bytes("abc")));
            assertEquals(1, nativeCalls.closes);
            nativeCalls.writeLimit = 2;
            nativeCalls.closeResult = -5;
            assertThrows(IOException.class, () -> fs.writeFile("/data", bytes("abc")));
            assertThrows(IOException.class, () -> fs.readFile("/data", 0, 4));
            assertEquals(3, nativeCalls.closes);
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void s6DirectoryPaginationUsesUtf8OrderIncludingHiddenNames() throws Exception {
        try (AgentFS fs = new FakeNative().client()) {
            AgentFS.DirectoryPage first = fs.listDirectory("/directory", 0, 2);
            assertEquals(java.util.List.of(".hidden", "a"),
                    first.entries.stream().map(e -> e.name).toList());
            assertTrue(first.truncated);
            assertEquals(2, first.nextOffset);
            AgentFS.DirectoryPage second = fs.listDirectory("/directory", first.nextOffset, 2);
            assertEquals(java.util.List.of("中文", "😀"),
                    second.entries.stream().map(e -> e.name).toList());
            assertFalse(second.truncated);
            assertTrue(fs.listDirectory("/directory", 10, 2).entries.isEmpty());
        }
    }

    @Test
    public void directoryContinuationIsConsumedWithoutDoubleClose() throws Exception {
        FakeNative calls = new FakeNative();
        calls.directoryMode = "two-pages";
        try (NativeClient client = calls.nativeClient()) {
            assertEquals(java.util.List.of("/directory/first", "/directory/second"),
                    client.list("/directory").stream().map(entry -> entry.path).toList());
            assertEquals(2, calls.directoryCalls);
            assertTrue(calls.closedFds.isEmpty());
        }
    }

    @Test
    public void invalidDirectoryNameClosesReturnedContinuation() throws Exception {
        FakeNative calls = new FakeNative();
        calls.directoryMode = "invalid-name";
        try (NativeClient client = calls.nativeClient()) {
            assertThrows(IOException.class, () -> client.list("/directory"));
            assertEquals(java.util.List.of(91), calls.closedFds);
        }
    }

    @Test
    public void continuationErrorDoesNotCloseAlreadyConsumedHandle() throws Exception {
        FakeNative calls = new FakeNative();
        calls.directoryMode = "second-error";
        try (NativeClient client = calls.nativeClient()) {
            assertThrows(IOException.class, () -> client.list("/directory"));
            assertEquals(2, calls.directoryCalls);
            assertTrue(calls.closedFds.isEmpty());
        }
    }

    @Test
    public void statAndDirectoryEntriesKeepMillisecondPrecision() throws Exception {
        try (NativeClient client = new FakeNative().nativeClient()) {
            assertEquals(1700000000123L, client.stat("/directory").mtimeMillis);
            for (SearchFiles.FileInfo entry : client.list("/directory")) {
                assertEquals(1700000000123L, entry.mtimeMillis);
            }
        }
    }

    @Test
    public void readErrorClosesDescriptorAndInvalidWriteDoesNotCreate() throws Exception {
        FakeNative calls = new FakeNative();
        calls.readError = true;
        try (AgentFS fs = calls.client()) {
            assertThrows(IOException.class, () -> fs.readFile("/data", 0, 4));
            assertEquals(java.util.List.of(11), calls.closedFds);
            assertThrows(IllegalArgumentException.class, () -> fs.writeFile("/data", null));
            assertEquals(0, calls.creates);
        }
    }

    static final class FakeNative {
        byte[] source = bytes("0123456789");
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int writeLimit = 2;
        int closeResult;
        int opens;
        int closes;
        int creates;
        boolean readError;
        String directoryMode = "single";
        int directoryCalls;
        final java.util.List<Integer> closedFds = new java.util.ArrayList<>();

        AgentFS client() {
            return new AgentFS(nativeClient());
        }

        NativeClient nativeClient() {
            NativeLibrary.Api api = (NativeLibrary.Api) Proxy.newProxyInstance(
                    NativeLibrary.Api.class.getClassLoader(), new Class<?>[] {NativeLibrary.Api.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "jfs_open": opens++; return 11;
                            case "jfs_create": creates++; return 11;
                            case "jfs_stat1":
                                putStat((Pointer) args[3], 0, true);
                                return 30;
                            case "jfs_listdir": return directory(args);
                            case "jfs_pread":
                                if (readError) return -5;
                                int offset = Math.toIntExact((Long) args[4]);
                                int count = Math.min(2, Math.min((Integer) args[3], Math.max(0, source.length - offset)));
                                if (count > 0) ((Pointer) args[2]).put(0, source, offset, count);
                                return count;
                            case "jfs_write":
                                int size = Math.min(writeLimit, (Integer) args[3]);
                                byte[] part = new byte[size];
                                ((Pointer) args[2]).get(0, part, 0, size);
                                written.write(part);
                                return size;
                            case "jfs_close": closes++; closedFds.add((Integer) args[1]); return closeResult;
                            case "jfs_term": return 0;
                            default: throw new AssertionError("Unexpected native call: " + method.getName());
                        }
                    });
            return new NativeClient(api, jnr.ffi.Runtime.getSystemRuntime(), 7);
        }

        private int directory(Object[] args) {
            Pointer buffer = (Pointer) args[4];
            directoryCalls++;
            if (directoryMode.equals("single")) return putDirectory(buffer);
            boolean first = directoryCalls == 1;
            assertEquals(first ? 7L : 91L, ((Long) args[1]).longValue());
            assertEquals(first ? 0L : 1L, ((Long) args[3]).longValue());
            if (!first && directoryMode.equals("second-error")) return -5;
            byte[] name = directoryMode.equals("invalid-name")
                    ? new byte[] {(byte) 0xff} : bytes(first ? "first" : "second");
            buffer.putByte(0, (byte) name.length);
            buffer.put(1, name, 0, name.length);
            buffer.putByte(1 + name.length, (byte) 30);
            putStat(buffer, 2 + name.length, false);
            int length = 32 + name.length;
            buffer.putInt(length, first ? 1 : 0);
            if (first) buffer.putInt(length + 4, 91);
            return length;
        }

        private static int putDirectory(Pointer buffer) {
            int position = 0;
            for (String name : new String[] {"😀", "中文", "a", ".hidden"}) {
                byte[] encoded = bytes(name);
                buffer.putByte(position++, (byte) encoded.length);
                buffer.put(position, encoded, 0, encoded.length);
                position += encoded.length;
                buffer.putByte(position++, (byte) 30);
                putStat(buffer, position, false);
                position += 30;
            }
            buffer.putInt(position, 0);
            return position;
        }

        private static void putStat(Pointer buffer, int offset, boolean directory) {
            buffer.putInt(offset, directory ? 1 << 31 : 0644);
            buffer.putLongLong(offset + 4, directory ? 0 : 10);
            buffer.putLongLong(offset + 12, 1700000000123L);
            buffer.putLongLong(offset + 20, 1700000000456L);
            buffer.putByte(offset + 28, (byte) 0);
            buffer.putByte(offset + 29, (byte) 0);
        }
    }
}
