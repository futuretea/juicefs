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
package io.juicefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.UUID;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Test;
import static org.junit.Assert.*;

/** Copied only into the isolated old-SDK regression build, never the old source tree. */
public class LegacyStatBoundaryTest {
    @Test
    public void legacyStatAndDirectoryCallsRejectOverflowAndRecover() throws Exception {
        Path root = new Path("/agentfs-stat-boundary-" + UUID.randomUUID());
        Path file = new Path(root, "data");
        byte[] expected = "cat\n".getBytes(StandardCharsets.UTF_8);
        try (FileSystem fs = client("hdfs", "supergroup")) {
            try (FSDataOutputStream stream = fs.create(file)) {
                stream.write(expected);
            }
        }
        String[][] identities = {
                {repeat("u", 50), repeat("g", 50)},
                {repeat("u", 51), repeat("g", 50)},
                {repeat("u", 50), repeat("g", 51)},
                {repeat("猫", 33), "g"}, {repeat("猫", 34), "g"}
        };
        for (String[] identity : identities) {
            String user = identity[0], group = identity[1];
            boolean fits = (user + group).getBytes(StandardCharsets.UTF_8).length <= 100;
            try (FileSystem fs = client(user, group)) {
                if (fits) {
                    assertIdentity(fs.getFileStatus(file), user, group);
                    assertIdentity(fs.getFileLinkStatus(file), user, group);
                    FileStatus[] listed = fs.listStatus(root);
                    assertEquals(1, listed.length);
                    assertIdentity(listed[0], user, group);
                } else {
                    assertErrno75(assertThrows(IOException.class, () -> fs.getFileStatus(file)));
                    assertErrno75(assertThrows(IOException.class, () -> fs.getFileLinkStatus(file)));
                    assertErrno75(assertThrows(IOException.class, () -> fs.listStatus(root)));
                }
                assertFile(fs, file, expected);
            }
        }
        try (FileSystem fs = client("hdfs", "supergroup")) {
            assertEquals(1, fs.listStatus(root).length);
            assertFile(fs, file, expected);
        }
    }

    private static void assertFile(FileSystem fs, Path path, byte[] expected) throws IOException {
        byte[] actual = new byte[expected.length];
        try (FSDataInputStream stream = fs.open(path)) {
            stream.readFully(0, actual);
        }
        assertArrayEquals(expected, actual);
    }

    private static FileSystem client(String user, String group) throws Exception {
        Configuration conf = new Configuration();
        conf.set("juicefs.superuser", user);
        conf.set("juicefs.supergroup", group);
        conf.set("juicefs.no-usage-report", "true");
        return UserGroupInformation.createUserForTesting(user, new String[] {group})
                .doAs((PrivilegedExceptionAction<FileSystem>) () ->
                        FileSystem.newInstance(FileSystem.getDefaultUri(conf), conf));
    }

    private static void assertIdentity(FileStatus status, String user, String group) {
        assertEquals(user, status.getOwner());
        assertEquals(group, status.getGroup());
    }

    private static void assertErrno75(IOException error) {
        assertTrue(error.toString(), error.getMessage().contains("75"));
    }

    private static String repeat(String value, int count) {
        String[] values = new String[count];
        Arrays.fill(values, value);
        return String.join("", values);
    }
}
