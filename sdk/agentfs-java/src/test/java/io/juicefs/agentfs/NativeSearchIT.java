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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeSearchIT {
    @Test
    public void s7NativeProvidersAgreeWithFixedOffsetsAcrossAllPages() throws Exception {
        String root = required("AGENTFS_SEARCH_ROOT") + "/corpus";
        try (AgentFS raw = client(new RawSearchProvider());
             AgentFS rg = client(new RipgrepSearchProvider("rg"))) {
            raw.writeFile(root + "/.hidden", bytes("aaaa"));
            raw.writeFile(root + "/nested/z", bytes("aaaa"));
            byte[] binary = new byte[(1 << 20) + 10];
            Arrays.fill(binary, (byte) 'x');
            byte[] query = bytes("猫\nZ");
            System.arraycopy(query, 0, binary, (1 << 20) - 2, query.length);
            binary[0] = (byte) 255;
            binary[1] = 0;
            raw.writeFile(root + "/binary", binary);
            List<String> overlaps = List.of(root + "/.hidden:0:2", root + "/.hidden:1:2",
                    root + "/.hidden:2:2", root + "/nested/z:0:2", root + "/nested/z:1:2",
                    root + "/nested/z:2:2");
            for (AgentFS fs : List.of(raw, rg)) {
                assertEquals(overlaps, all(fs, root, "aa"));
                assertEquals(List.of(root + "/binary:1048574:5"), all(fs, root, "猫\nZ"));
                assertTrue(all(fs, root, "never-present").isEmpty());
            }
        }
    }

    @Test
    public void s17CustomProviderReceivesNativeReadOnlyFilesAndHonorsClientClose() throws Exception {
        String root = required("AGENTFS_SEARCH_ROOT") + "/custom";
        AtomicInteger calls = new AtomicInteger();
        SearchProvider custom = (files, query, directory, limit, cursor) -> {
            calls.incrementAndGet();
            assertEquals(root, directory);
            assertEquals("directory", files.stat(directory).type);
            List<SearchFiles.FileInfo> entries = files.list(directory);
            assertEquals(1, entries.size());
            assertEquals(directory + "/data", entries.get(0).path);
            try (InputStream input = files.open(entries.get(0).path, 2)) {
                assertArrayEquals(bytes("cdef"), input.readAllBytes());
            }
            return new AgentFS.SearchPage(List.of(new AgentFS.SearchMatch(directory + "/data", 2, 4)),
                    null, true, List.of());
        };
        try (AgentFS fs = client(custom)) {
            fs.writeFile(root + "/data", bytes("abcdef"));
            AgentFS.SearchPage result = fs.search("custom", root, 10, null);
            assertEquals(1, result.matches.size());
            assertEquals(2, result.matches.get(0).offset);
            fs.close();
            assertThrows(IOException.class, () -> fs.search("custom", root, 10, null));
            assertEquals(1, calls.get());
        }
    }

    private static List<String> all(AgentFS fs, String root, String query) throws Exception {
        List<String> matches = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 20; pageNumber++) {
            AgentFS.SearchPage page = fs.search(query, root, 2, cursor);
            assertTrue(page.skipped.isEmpty());
            assertTrue(page.matches.size() <= 2);
            page.matches.forEach(match -> matches.add(match.path + ":" + match.offset + ":" + match.length));
            cursor = page.nextCursor;
            if (cursor == null) {
                assertTrue(page.complete);
                return matches;
            }
            assertFalse(page.complete);
        }
        throw new AssertionError("Pagination did not finish");
    }

    private static AgentFS client(SearchProvider provider) throws IOException {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity("hdfs", List.of("supergroup")).searchProvider(provider).open();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull("Required integration environment is absent: " + name, value);
        assertFalse("Required integration environment is empty: " + name, value.isEmpty());
        return value;
    }
}
