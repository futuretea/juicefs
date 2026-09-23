/*
 * JuiceFS, Copyright 2026 Juicedata, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.juicefs.agentfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import jnr.ffi.Memory;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run only with the repaired native library; the independent Go test owns Red. */
public class NativeStatBoundaryIT {
    @Test
    public void exactIdentityBytesWorkAndOverflowReturnsErrorsWithoutChangingData() throws Exception {
        String root = required("AGENTFS_TEST_ROOT") + "/stat-boundary-" + UUID.randomUUID();
        byte[] original = "cat\n".getBytes(StandardCharsets.UTF_8);
        try (AgentFS owner = client("hdfs", "supergroup")) {
            owner.writeFile(root + "/a", original);
            owner.writeFile(root + "/b", original);
        }
        String[][] identities = {
                {"u".repeat(50), "g".repeat(50)},
                {"u".repeat(51), "g".repeat(50)},
                {"u".repeat(50), "g".repeat(51)},
                {"猫".repeat(33), "g"}, {"猫".repeat(34), "g"}
        };
        for (String[] identity : identities) {
            String user = identity[0], group = identity[1];
            boolean fits = (user + group).getBytes(StandardCharsets.UTF_8).length <= 100;
            try (AgentFS fs = client(user, group)) {
                if (fits) {
                    assertEquals(2, fs.listDirectory(root, 0, 10).entries.size());
                    assertEquals(0, fs.editFile(root + "/a", "cat", "cat").replacements);
                    assertTrue(fs.search("cat", root, 10, null).complete);
                } else {
                    assertErrno75(assertThrows(IOException.class, () -> fs.listDirectory(root, 0, 10)));
                    EditException edit = assertThrows(EditException.class,
                            () -> fs.editFile(root + "/a", "cat", "dog"));
                    assertEquals("storage_error", edit.code);
                    assertEquals("unchanged", edit.outcome);
                    assertErrno75(edit.getCause());
                    AgentFS.SearchPage page = fs.search("cat", root, 10, null);
                    assertFalse(page.complete);
                    assertTrue(page.matches.isEmpty());
                    assertEquals(1, page.skipped.size());
                    assertEquals(root, page.skipped.get(0).path);
                    assertEquals("list_error", page.skipped.get(0).reason);
                }
                assertArrayEquals(original, fs.readFile(root + "/a", 0, 10).data);
            }
            assertNativeEntries(root, user, group, fits);
        }
        try (AgentFS recovered = client("hdfs", "supergroup")) {
            assertEquals(2, recovered.listDirectory(root, 0, 10).entries.size());
            assertArrayEquals(original, recovered.readFile(root + "/a", 0, 10).data);
        }
    }

    private static void assertNativeEntries(String root, String user, String group, boolean fits)
            throws Exception {
        NativeLibrary.Api api = NativeLibrary.load();
        Runtime runtime = Runtime.getRuntime(api);
        long handle = api.jfs_init(null, 0, required("AGENTFS_VOLUME"),
                NativeConfig.json(required("AGENTFS_META")), user, group, user, group);
        assertTrue(handle > 0);
        try (NativeClient owned = new NativeClient(api, runtime, handle)) {
            for (boolean link : new boolean[] {false, true}) {
                Pointer buffer = Memory.allocateDirect(runtime, 130);
                byte[] before = new byte[130];
                Arrays.fill(before, (byte) 0x5a);
                buffer.put(0, before, 0, before.length);
                int result = link ? api.jfs_lstat1(NativeClient.pid(), handle, root + "/a", buffer)
                        : api.jfs_stat1(NativeClient.pid(), handle, root + "/a", buffer);
                if (fits) {
                    byte[] names = (user + "\0" + group + "\0").getBytes(StandardCharsets.UTF_8);
                    assertEquals(28 + names.length, result);
                    byte[] actual = new byte[names.length];
                    buffer.get(28, actual, 0, actual.length);
                    assertArrayEquals(names, actual);
                } else {
                    assertEquals(-75, result);
                    byte[] actual = new byte[130];
                    buffer.get(0, actual, 0, actual.length);
                    assertArrayEquals(before, actual);
                }
            }
            Pointer listing = Memory.allocateDirect(runtime, 4096);
            int result = api.jfs_listdir(NativeClient.pid(), handle, root, 0, listing, 4096);
            if (fits) {
                assertTrue(result > 0);
                assertEquals(0, listing.getInt(result));
            } else {
                assertEquals(-75, result);
                // An eight-byte first page deliberately returns only the continuation.
                assertEquals(0, api.jfs_listdir(NativeClient.pid(), handle, root, 0, listing, 8));
                assertEquals(2, listing.getInt(0));
                int continuation = listing.getInt(4);
                assertTrue(continuation > 0);
                // Resume at the second entry to exercise the consumed-handle error branch.
                assertEquals(-75, api.jfs_listdir(NativeClient.pid(), continuation, root, 1, listing, 4096));
                // This checks registry consumption, not an assertion of OS-level leak freedom.
                assertEquals(-22, api.jfs_listdir(NativeClient.pid(), continuation, root, 1, listing, 4096));
            }
        }
    }

    private static void assertErrno75(Throwable error) {
        assertNotNull(error);
        assertTrue(error.toString(), error.getMessage().contains("75"));
    }

    private static AgentFS client(String user, String group) throws IOException {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity(user, List.of(group)).superuser(user, group).open();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull("Missing integration environment: " + name, value);
        assertFalse(value.isEmpty());
        return value;
    }
}
