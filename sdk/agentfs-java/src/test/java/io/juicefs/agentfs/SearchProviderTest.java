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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchProviderTest {
    private static List<SearchProvider> providers() {
        return List.of(new RawSearchProvider(), new RipgrepSearchProvider("rg"));
    }

    @Test
    public void overlapsAndHiddenFilesUseExactSortedByteOffsets() throws Exception {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/z", "aaaa");
        files.put("/root/.hidden", "aaaa");
        for (SearchProvider provider : providers()) {
            assertEquals(List.of("/root/.hidden:0:2", "/root/.hidden:1:2", "/root/.hidden:2:2",
                    "/root/z:0:2", "/root/z:1:2", "/root/z:2:2"), all(provider, files, "aa", 2));
        }
    }

    @Test
    public void crossesWindowAndPreservesBinaryMultilineAndUtf8Semantics() throws Exception {
        MemoryFiles files = new MemoryFiles();
        byte[] content = new byte[(1 << 20) + 10];
        Arrays.fill(content, (byte) 'x');
        byte[] needle = "猫\nZ".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(needle, 0, content, (1 << 20) - 2, needle.length);
        content[0] = (byte) 0xff;
        content[1] = 0;
        files.data.put("/root/binary", content);
        for (SearchProvider provider : providers()) {
            assertEquals(List.of("/root/binary:1048574:5"), all(provider, files, "猫\nZ", 1));
            assertTrue(all(provider, files, "missing", 10).isEmpty());
        }
    }

    @Test
    public void cursorIsBoundToEngineQueryAndRoot() throws Exception {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/a", "aaaa");
        SearchProvider raw = new RawSearchProvider();
        String cursor = raw.search(files, "aa", "/root", 1, null).nextCursor;
        assertNotNull(cursor);
        IllegalArgumentException crossProvider = assertThrows(IllegalArgumentException.class,
                () -> new RipgrepSearchProvider("rg").search(files, "aa", "/root", 1, cursor));
        assertEquals("invalid cursor", crossProvider.getMessage());
        IllegalArgumentException wrongQuery = assertThrows(IllegalArgumentException.class,
                () -> raw.search(files, "a", "/root", 1, cursor));
        assertEquals("cursor does not belong to this search", wrongQuery.getMessage());
        IllegalArgumentException wrongRoot = assertThrows(IllegalArgumentException.class,
                () -> raw.search(files, "aa", "/other", 1, cursor));
        assertEquals("cursor does not belong to this search", wrongRoot.getMessage());
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> raw.search(files, "aa", "/root", 1, "garbage"));
        assertEquals("invalid cursor", malformed.getMessage());
    }

    @Test
    public void limitMustBeAPositiveInteger() {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/a", "needle");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RawSearchProvider().search(files, "needle", "/root", 0, null));
        assertEquals("limit must be a positive integer", error.getMessage());
    }

    @Test
    public void fileRootIsSkippedAsNonDirectory() throws Exception {
        for (SearchProvider provider : providers()) {
            MemoryFiles files = new MemoryFiles();
            files.put("/root/a", "needle");
            AgentFS.SearchPage page = provider.search(files, "needle", "/root/a", 10, null);
            assertFalse(page.complete);
            assertTrue(page.matches.isEmpty());
            assertEquals(1, page.skipped.size());
            assertEquals("/root/a", page.skipped.get(0).path);
            assertEquals("non_directory_root", page.skipped.get(0).reason);
            assertNull(page.skipped.get(0).errno);
        }
    }

    @Test
    public void skipsCarryNativeErrnoWhenAvailable() throws Exception {
        SearchFiles failing = new SearchFiles() {
            @Override
            public FileInfo stat(String path) throws IOException {
                throw new NativeMetadata.NativeError("stat", path, 13);
            }

            @Override
            public List<FileInfo> list(String path) {
                return List.of();
            }

            @Override
            public InputStream open(String path, long offset) {
                return new ByteArrayInputStream(new byte[0]);
            }
        };
        for (SearchProvider provider : providers()) {
            AgentFS.SearchPage page = provider.search(failing, "needle", "/root", 10, null);
            assertFalse(page.complete);
            assertEquals(1, page.skipped.size());
            assertEquals("list_error", page.skipped.get(0).reason);
            assertEquals(Integer.valueOf(13), page.skipped.get(0).errno);
        }
    }

    @Test
    public void gapsAreReportedWithoutClaimingComplete() throws Exception {
        for (SearchProvider provider : providers()) {
            for (String fault : List.of("list_error", "read_error", "changed")) {
                MemoryFiles files = new MemoryFiles();
                files.put("/root/a", "needle");
                files.fault = fault;
                AgentFS.SearchPage page = provider.search(files, "needle", "/root", 10, null);
                assertFalse(fault, page.complete);
                assertTrue(fault, page.skipped.stream().anyMatch(skip -> skip.reason.equals(fault)));
            }
        }
    }

    @Test
    public void missingRipgrepFailsWithoutRawFallback() {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/a", "needle");
        SearchProviderException error = assertThrows(SearchProviderException.class,
                () -> new RipgrepSearchProvider(
                        "/agentfs-nonexistent-rg").search(files, "needle", "/root", 10, null));
        assertEquals("could not execute ripgrep", error.getMessage());
    }

    @Test
    public void ripgrepExitCodeDecidesDespiteStderr() throws Exception {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/a", "aaaa");
        String script = fakeRipgrep("#!/bin/sh", "cat >/dev/null", "echo warning noise >&2", "exit 1");
        AgentFS.SearchPage page = new RipgrepSearchProvider(script).search(files, "aa", "/root", 10, null);
        assertTrue(page.complete);
        assertTrue(page.matches.isEmpty());
    }

    @Test
    public void ripgrepFailureReportsExitAndStderr() throws Exception {
        MemoryFiles files = new MemoryFiles();
        files.put("/root/a", "aaaa");
        String script = fakeRipgrep("#!/bin/sh", "cat >/dev/null", "echo bad pattern >&2", "exit 2");
        SearchProviderException error = assertThrows(SearchProviderException.class,
                () -> new RipgrepSearchProvider(script).search(files, "aa", "/root", 10, null));
        assertTrue(error.getMessage(), error.getMessage().startsWith("ripgrep failed (exit 2)"));
        assertTrue(error.getMessage(), error.getMessage().contains("bad pattern"));
    }

    @Test
    public void ripgrepOutputIsStrictlyValidated() throws Exception {
        String[][] cases = {
                {"invalid ripgrep offset output", "printf 'garbage\\n'"},
                {"invalid ripgrep match offset", "printf '1:X\\n1:X\\n'"},
                {"invalid ripgrep match offset", "printf '3:X\\n'"},
        };
        for (String[] pair : cases) {
            MemoryFiles files = new MemoryFiles();
            files.put("/root/a", "aaaa");
            String script = fakeRipgrep("#!/bin/sh", "cat >/dev/null", pair[1]);
            SearchProviderException error = assertThrows(SearchProviderException.class,
                    () -> new RipgrepSearchProvider(script).search(files, "aa", "/root", 10, null));
            assertEquals(pair[0], error.getMessage());
        }
    }

    private static String fakeRipgrep(String... lines) throws IOException {
        java.nio.file.Path script = java.nio.file.Files.createTempFile("fake-rg", ".sh");
        java.nio.file.Files.write(script, Arrays.asList(lines), StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("cannot mark fake ripgrep executable");
        }
        return script.toString();
    }

    private static java.io.File sharedSearchFixtures() {
        String property = System.getProperty("agentfs.search.fixtures");
        if (property != null) return new java.io.File(property);
        // Surefire forks with the module directory as working directory; the second
        // candidate keeps the canonical fixture reachable from target/test-classes.
        for (String candidate : new String[]{"../agentfs/fixtures/search_cases.json",
                "../../../agentfs/fixtures/search_cases.json"}) {
            java.io.File file = new java.io.File(candidate);
            if (file.isFile()) return file;
        }
        return new java.io.File("../agentfs/fixtures/search_cases.json");
    }

    private static final java.util.Set<String> SUPPORTS =
            new java.util.HashSet<>(Arrays.asList("set_mtime", "inject_read_error", "symlink", "mutate_read"));
    private static final int MAX_SKIPPED = 0;

    @Test
    public void sharedSearchFixturesPassOnBothProviders() throws Exception {
        org.json.JSONArray cases;
        try (InputStream input = new java.io.FileInputStream(sharedSearchFixtures())) {
            cases = new org.json.JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getJSONArray("cases");
        }
        assertEquals("Do not silently omit shared search fixtures", 17, cases.length());
        List<String> skipped = new ArrayList<>();
        for (int index = 0; index < cases.length(); index++) {
            org.json.JSONObject item = cases.getJSONObject(index);
            List<String> unsupported = new ArrayList<>();
            if (item.has("requires")) {
                for (Object need : item.getJSONArray("requires")) {
                    if (!SUPPORTS.contains((String) need)) {
                        unsupported.add((String) need);
                    }
                }
            }
            if (!unsupported.isEmpty()) {
                skipped.add(item.getString("id") + unsupported);
                continue;
            }
            for (SearchProvider provider : providers()) {
                runSharedCase(provider, item);
            }
        }
        assertTrue("unsupported cases skipped: " + skipped, skipped.size() <= MAX_SKIPPED);
    }

    private static void runSharedCase(SearchProvider provider, org.json.JSONObject item) throws Exception {
        String id = item.getString("id");
        String providerName = provider instanceof RawSearchProvider ? "raw" : "ripgrep";
        MemoryFiles files = new MemoryFiles();
        files.dirs.add("/");
        if (item.has("files")) {
            for (Object value : item.getJSONArray("files")) {
                org.json.JSONObject entry = (org.json.JSONObject) value;
                String path = entry.getString("path");
                for (int slash = path.indexOf('/', 1); slash > 0; slash = path.indexOf('/', slash + 1)) {
                    files.dirs.add(path.substring(0, slash));
                }
                if (entry.has("symlink_to")) {
                    files.types.put(path, "other");
                } else {
                    files.data.put(path, java.util.Base64.getDecoder().decode(entry.getString("content_b64")));
                }
                if (entry.has("mtime")) {
                    files.mtimes.put(path, entry.getLong("mtime") * 1000);
                }
            }
        }
        if (item.has("requires")) {
            for (Object need : item.getJSONArray("requires")) {
                if (need.equals("inject_read_error")) {
                    files.failOpen = true;
                }
            }
        }
        if (item.has("mutate")) {
            org.json.JSONObject mutate = item.getJSONObject("mutate");
            files.mutatePath = mutate.getString("path");
            files.mutateBytes = java.util.Base64.getDecoder().decode(mutate.getString("content_b64"));
            files.mutateMtime = mutate.has("mtime") ? mutate.getLong("mtime") * 1000 : -1;
        }
        String query = item.getString("query");
        String root = item.has("root") ? item.getString("root") : "/";
        int limit = item.has("limit") ? item.getInt("limit") : 100;
        String cursor = null;
        if (item.has("pages")) {
            for (Object value : item.getJSONArray("pages")) {
                org.json.JSONObject expected = (org.json.JSONObject) value;
                AgentFS.SearchPage page = provider.search(files, query, root, limit, cursor);
                assertSharedPage(id, providerName, expected, page);
                cursor = page.nextCursor;
            }
        }
        // Error expectations cover the public facade contract (root rejection
        // lives there); these paths throw before any filesystem access.
        if (item.has("error") || item.has("then")) {
            try (AgentFS facade = new AgentFS(new FileOperationsTest.FakeNative().nativeClient(), provider)) {
                if (item.has("error")) {
                    assertSharedError(id, facade, query, root, limit, cursor,
                            item.getJSONObject("error").getString("message"));
                }
                if (item.has("then")) {
                    for (Object value : item.getJSONArray("then")) {
                        org.json.JSONObject follow = (org.json.JSONObject) value;
                        String followCursor = cursor;
                        if (follow.has("cursor")) {
                            Object explicit = follow.get("cursor");
                            followCursor = explicit instanceof org.json.JSONObject
                                    ? ((org.json.JSONObject) explicit).getString(providerName) : (String) explicit;
                        }
                        assertSharedError(id, facade,
                                follow.has("query") ? follow.getString("query") : query,
                                follow.has("root") ? follow.getString("root") : root,
                                follow.has("limit") ? follow.getInt("limit") : limit,
                                followCursor, follow.getJSONObject("error").getString("message"));
                    }
                }
            }
        }
    }

    private static void assertSharedPage(String id, String providerName, org.json.JSONObject expected,
            AgentFS.SearchPage page) {
        org.json.JSONArray matches = expected.getJSONArray("matches");
        assertEquals(id + " matches", matches.length(), page.matches.size());
        for (int index = 0; index < matches.length(); index++) {
            org.json.JSONObject match = matches.getJSONObject(index);
            assertEquals(id + " match path", match.getString("path"), page.matches.get(index).path);
            assertEquals(id + " match offset", match.getLong("offset"), page.matches.get(index).offset);
            assertEquals(id + " match length", match.getInt("length"), page.matches.get(index).length);
        }
        org.json.JSONArray skips = expected.has("skipped") ? expected.getJSONArray("skipped")
                : new org.json.JSONArray();
        assertEquals(id + " skipped", skips.length(), page.skipped.size());
        for (int index = 0; index < skips.length(); index++) {
            org.json.JSONObject skip = skips.getJSONObject(index);
            assertEquals(id + " skip path", skip.getString("path"), page.skipped.get(index).path);
            assertEquals(id + " skip reason", skip.getString("reason"), page.skipped.get(index).reason);
            if (skip.has("errno")) {
                assertEquals(id + " skip errno", Integer.valueOf(skip.getInt("errno")),
                        page.skipped.get(index).errno);
            } else {
                assertNull(id + " skip errno", page.skipped.get(index).errno);
            }
        }
        assertEquals(id + " complete", expected.getBoolean("complete"), page.complete);
        assertEquals(id + " next_cursor", expected.getBoolean("next_cursor"), page.nextCursor != null);
        if (expected.has("expect_next_cursor")) {
            assertEquals(id + " exact cursor",
                    expected.getJSONObject("expect_next_cursor").getString(providerName), page.nextCursor);
        }
    }

    private static void assertSharedError(String id, AgentFS facade, String query,
            String root, int limit, String cursor, String message) {
        IllegalArgumentException error = assertThrows(id + " must fail with: " + message,
                IllegalArgumentException.class, () -> facade.search(query, root, limit, cursor));
        assertEquals(id, message, error.getMessage());
    }

    private static List<String> all(SearchProvider provider, MemoryFiles files, String query, int limit)
            throws Exception {
        List<String> matches = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 100; pageNumber++) {
            AgentFS.SearchPage page = provider.search(files, query, "/root", limit, cursor);
            assertTrue(page.matches.size() <= limit);
            assertTrue(page.skipped.isEmpty());
            for (AgentFS.SearchMatch match : page.matches) {
                matches.add(match.path + ":" + match.offset + ":" + match.length);
            }
            cursor = page.nextCursor;
            if (cursor == null) {
                assertTrue(page.complete);
                return matches;
            }
            assertFalse(page.complete);
        }
        throw new AssertionError("Pagination did not terminate");
    }

    static final class MemoryFiles implements SearchFiles {
        final Map<String, byte[]> data = new LinkedHashMap<>();
        final Map<String, Long> mtimes = new java.util.HashMap<>();
        final Map<String, String> types = new java.util.HashMap<>();
        final java.util.Set<String> dirs = new java.util.HashSet<>(List.of("/root"));
        String fault = "";
        boolean opened;
        boolean failOpen;
        String mutatePath;
        byte[] mutateBytes;
        long mutateMtime = -1;

        void put(String path, String text) { data.put(path, text.getBytes(StandardCharsets.UTF_8)); }
        public FileInfo stat(String path) throws IOException {
            if (dirs.contains(path)) return new FileInfo(path, "directory", 0, 1000);
            String forced = types.get(path);
            if (forced != null) return new FileInfo(path, forced, 0, mtimes.getOrDefault(path, 1000L));
            byte[] content = data.get(path);
            if (content == null) throw new NativeMetadata.NativeError("stat", path, 2);
            long fallback = opened && fault.equals("changed") ? 1001L : 1000L;
            return new FileInfo(path, "file", content.length, mtimes.getOrDefault(path, fallback));
        }
        public List<FileInfo> list(String path) throws IOException {
            if (fault.equals("list_error")) throw new IOException("synthetic list failure");
            if (!dirs.contains(path)) throw new IOException("path is not a directory: " + path);
            List<FileInfo> result = new ArrayList<>();
            for (String child : children(path)) result.add(stat(child));
            return result;
        }
        private List<String> children(String path) {
            List<String> result = new ArrayList<>();
            String prefix = path.equals("/") ? "/" : path + "/";
            for (String candidate : allPaths()) {
                if (candidate.equals(path) || !candidate.startsWith(prefix)) continue;
                if (candidate.indexOf('/', prefix.length()) < 0) result.add(candidate);
            }
            return result;
        }
        private List<String> allPaths() {
            List<String> all = new ArrayList<>(data.keySet());
            all.addAll(types.keySet());
            for (String dir : dirs) {
                if (!dir.equals("/")) all.add(dir);
            }
            return all;
        }
        public InputStream open(String path, long offset) throws IOException {
            if (failOpen) throw new NativeMetadata.NativeError("open", path, 13);
            if (fault.equals("read_error")) throw new IOException("synthetic read failure");
            if (mutatePath != null && mutatePath.equals(path)) {
                data.put(path, mutateBytes);
                mtimes.put(path, mutateMtime);
                mutatePath = null;
            }
            opened = true;
            byte[] content = data.get(path);
            if (content == null) throw new NativeMetadata.NativeError("open", path, 2);
            int start = (int) Math.min(offset, content.length);
            return new ByteArrayInputStream(content, start, content.length - start);
        }
    }
}
