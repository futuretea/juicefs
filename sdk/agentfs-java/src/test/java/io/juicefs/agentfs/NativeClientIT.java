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
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Real native storage only; invoke explicitly with -Dtest=NativeClientIT. */
public class NativeClientIT {
    @Test
    public void s1s2CloseOneClientLeavesAnotherUsable() throws Exception {
        String path = required("AGENTFS_TEST_ROOT") + "/client-lifecycle.txt";
        byte[] expected = "independent native client\n".getBytes(StandardCharsets.UTF_8);
        try (AgentFS first = client("hdfs", "supergroup");
             AgentFS second = client("hdfs", "supergroup")) {
            first.writeFile(path, expected);
            assertArrayEquals(expected, first.readFile(path, 0, 4096).data);
            first.close();
            first.close();
            assertArrayEquals(expected, second.readFile(path, 0, 4096).data);
            assertThrows(IOException.class, () -> first.readFile(path, 0, 4096));
            assertThrows(IOException.class, () -> first.writeFile(path, expected));
            assertArrayEquals(expected, second.readFile(path, 0, 4096).data);
        }
    }

    @Test
    public void s3UnprivilegedReadAndWriteAreDeniedWithoutChangingData() throws Exception {
        String path = required("AGENTFS_DENIED_FILE");
        String root = required("AGENTFS_TEST_ROOT");
        assertTrue("Denied fixture must be inside this run's test root", path.startsWith(root + "/"));
        try (AgentFS owner = client("hdfs", "supergroup");
             AgentFS restricted = client("agentfs-unprivileged", "agentfs-unprivileged")) {
            byte[] original = owner.readFile(path, 0, 4096).data;
            assertTrue("Denied fixture must contain known bytes", original.length > 0);
            assertThrows(IOException.class, () -> restricted.readFile(path, 0, 4096));
            assertThrows(IOException.class, () -> restricted.writeFile(path, new byte[] {42}));
            assertArrayEquals(original, owner.readFile(path, 0, 4096).data);
        }
    }

    @Test
    public void writeFileRejectsExistingEmptyDirectory() throws Exception {
        String path = required("AGENTFS_DIRECTORY_PATH");
        String root = required("AGENTFS_TEST_ROOT");
        assertTrue("Directory fixture must be inside this run's test root", path.startsWith(root + "/"));
        try (AgentFS owner = client("hdfs", "supergroup")) {
            assertThrows(IOException.class, () -> owner.writeFile(path, new byte[] {42}));
            assertThrows(IOException.class, () -> owner.readFile(path, 0, 1));
        }
    }

    @Test
    public void restrictedClientStaysRestrictedAfterPrivilegedClientOpens() throws Exception {
        String path = required("AGENTFS_DENIED_FILE");
        assertTrue(path.startsWith(required("AGENTFS_TEST_ROOT") + "/"));
        try (AgentFS restricted = client("agentfs-unprivileged", "agentfs-unprivileged");
             AgentFS owner = client("hdfs", "supergroup")) {
            byte[] original = owner.readFile(path, 0, 4096).data;
            assertTrue(original.length > 0);
            assertThrows(IOException.class, () -> restricted.readFile(path, 0, 4096));
            assertThrows(IOException.class, () -> restricted.writeFile(path, new byte[] {42}));
            assertArrayEquals(original, owner.readFile(path, 0, 4096).data);
            owner.close();
            assertThrows(IOException.class, () -> restricted.readFile(path, 0, 4096));
        }
    }

    private static AgentFS client(String user, String group) throws IOException {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity(user, List.of(group)).open();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull("Required integration environment is absent: " + name, value);
        assertTrue("Required integration environment is empty: " + name, !value.isEmpty());
        return value;
    }
}
