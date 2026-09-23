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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** V2 uses only a new, externally assigned synthetic root and real native storage. */
public class NativeFilesIT {
    @Test
    public void s4s5CreateParentsReplaceAndReadBoundaries() throws Exception {
        String path = required("AGENTFS_FILES_ROOT") + "/parents/deeper/data";
        try (AgentFS fs = client()) {
            fs.writeFile(path, bytes("0123456789"));
            AgentFS.ReadResult read = fs.readFile(path, 2, 4);
            assertArrayEquals(bytes("2345"), read.data);
            assertEquals(6, read.nextOffset);
            assertTrue(read.truncated);
            fs.writeFile(path, bytes("abc"));
            assertArrayEquals(bytes("abc"), fs.readFile(path, 0, 100).data);
            assertFalse(fs.readFile(path, 0, 3).truncated);
            assertArrayEquals(new byte[0], fs.readFile(path, 20, 4).data);
            fs.writeFile(path, new byte[0]);
            assertArrayEquals(new byte[0], fs.readFile(path, 0, 4).data);
            assertFalse(fs.readFile(path, 0, 4).truncated);
        }
    }

    @Test
    public void s6Utf8NamesPageWithoutDuplicatesOrMissingEntries() throws Exception {
        String directory = required("AGENTFS_FILES_ROOT") + "/ordered";
        try (AgentFS fs = client()) {
            for (String name : List.of("😀", "中文", "a", ".hidden")) {
                fs.writeFile(directory + "/" + name, bytes(name));
            }
            List<String> names = new ArrayList<>();
            long offset = 0;
            for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
                AgentFS.DirectoryPage page = fs.listDirectory(directory, offset, 2);
                page.entries.forEach(entry -> names.add(entry.name));
                if (!page.truncated) break;
                assertTrue(page.nextOffset > offset);
                offset = page.nextOffset;
            }
            assertEquals(List.of(".hidden", "a", "中文", "😀"), names);
        }
    }

    @Test
    public void s19NormalizeWithoutChangingSpecialCharacterNames() throws Exception {
        String root = required("AGENTFS_FILES_ROOT");
        try (AgentFS fs = client()) {
            for (String name : List.of("a%20b", "a b", "a#b", "a?b", "a\\b", "中文")) {
                String canonical = root + "/special/" + name;
                String input = root + "/special//./" + name;
                assertEquals(canonical, fs.writeFile(input, bytes(name)).path);
                assertArrayEquals(bytes(name), fs.readFile(canonical, 0, 100).data);
            }
            String canonical = root + "/cleaned";
            fs.writeFile(root + "/unused/../cleaned", bytes("cleaned"));
            assertArrayEquals(bytes("cleaned"), fs.readFile(canonical, 0, 100).data);
            // Native cleaning resolves above-root '..' while Java preserves its display form.
            String aboveRoot = "/../.." + canonical;
            AgentFS.ReadResult result = fs.readFile(aboveRoot, 0, 100);
            assertEquals(aboveRoot, result.path);
            assertArrayEquals(bytes("cleaned"), result.data);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void s6LargeDirectorySpansNativeBuffersWithoutMissingEntries() throws Exception {
        String directory = required("AGENTFS_FILES_ROOT") + "/large-directory";
        List<String> expected = new ArrayList<>();
        try (AgentFS fs = client()) {
            for (int index = 0; index < 600; index++) {
                String name = String.format(java.util.Locale.ROOT, "%04d-", index) + "long-name-".repeat(8);
                expected.add(name);
                fs.writeFile(directory + "/" + name, new byte[0]);
            }
            List<String> actual = new ArrayList<>();
            long offset = 0;
            for (int pageNumber = 0; pageNumber < 10; pageNumber++) {
                AgentFS.DirectoryPage page = fs.listDirectory(directory, offset, 97);
                page.entries.forEach(entry -> actual.add(entry.name));
                if (!page.truncated) break;
                assertTrue(page.nextOffset > offset);
                offset = page.nextOffset;
            }
            assertEquals(expected, actual);
        }
    }

    private static AgentFS client() throws Exception {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity("hdfs", List.of("supergroup")).open();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull("Required integration environment is absent: " + name, value);
        assertFalse("Required integration environment is empty: " + name, value.isEmpty());
        return value;
    }
}
