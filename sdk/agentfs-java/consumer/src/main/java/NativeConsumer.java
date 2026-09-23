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
import io.juicefs.agentfs.AgentFS;
import io.juicefs.agentfs.SearchProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Runs with only compiled consumer classes and Maven-resolved runtime JARs. */
public final class NativeConsumer {
    public static void main(String[] args) throws Exception {
        String root = required("AGENTFS_TEST_ROOT");
        String denied = required("AGENTFS_DENIED_FILE");
        byte[] expected = "standalone packaged client\n".getBytes(StandardCharsets.UTF_8);
        try (AgentFS first = client("hdfs", "supergroup");
             AgentFS second = client("hdfs", "supergroup");
             AgentFS restricted = client("agentfs-unprivileged", "agentfs-unprivileged")) {
            String path = root + "/packaged-client.txt";
            first.writeFile(path, expected);
            verify(expected, first.readFile(path, 0, 4096).data);
            first.close();
            first.close();
            verify(expected, second.readFile(path, 0, 4096).data);
            try {
                first.readFile(path, 0, 4096);
                throw new AssertionError("Closed client remained usable");
            } catch (IOException expectedFailure) {
                // Closed instances cannot access storage.
            }
            byte[] original = second.readFile(denied, 0, 4096).data;
            try {
                restricted.writeFile(denied, new byte[] {42});
                throw new AssertionError("Unauthorized write succeeded");
            } catch (IOException expectedFailure) {
                if (!expectedFailure.getMessage().contains("errno 13")) {
                    throw expectedFailure;
                }
            }
            verify(original, second.readFile(denied, 0, 4096).data);
            verifyOperations(second, root + "/six-operations/data.txt");
        }
        System.err.println("S1/S2/S3/S17 packaged native consumer: passed");
    }

    private static void verifyOperations(AgentFS fs, String path) throws Exception {
        fs.writeFile(path, "aaaa".getBytes(StandardCharsets.UTF_8));
        verify("aaaa".getBytes(StandardCharsets.UTF_8), fs.readFile(path, 0, 4).data);
        AgentFS.DirectoryPage directory = fs.listDirectory(path.substring(0, path.lastIndexOf('/')), 0, 10);
        if (directory.entries.size() != 1 || !directory.entries.get(0).name.equals("data.txt")) {
            throw new AssertionError("Directory contents differ");
        }
        List<Long> offsets = new ArrayList<>();
        String cursor = null;
        do {
            AgentFS.SearchPage page = fs.search("aa", path.substring(0, path.lastIndexOf('/')), 2, cursor);
            if (page.complete != (page.nextCursor == null) || !page.skipped.isEmpty()) {
                throw new AssertionError("Unexpected search completion state");
            }
            for (AgentFS.SearchMatch match : page.matches) offsets.add(match.offset);
            cursor = page.nextCursor;
        } while (cursor != null);
        if (!offsets.equals(List.of(0L, 1L, 2L))) throw new AssertionError("Search offsets differ");
        fs.editFile(path, "aaaa", "cat\ndog");
        fs.applyPatch(path, "*** Begin Patch\n*** Update File: " + path
                + "\n@@\n cat\n-dog\n*** End of File\n*** End Patch\n");
        String edited = new String(fs.readFile(path, 0, 100).data, StandardCharsets.UTF_8);
        if (!edited.equals("cat\n")) throw new AssertionError("Unchanged context newline was lost");
        verifyCustomProvider(path);
        System.out.println(new JSONObject().put("operations", List.of("readFile", "writeFile",
                "listDirectory", "search", "editFile", "applyPatch"))
                .put("search_offsets", offsets).put("edited_text", edited));
    }

    private static void verifyCustomProvider(String path) throws Exception {
        boolean[] called = {false};
        SearchProvider provider = (files, query, root, limit, cursor) -> {
            called[0] = true;
            if (files.stat(root).size != 4) throw new AssertionError("Provider cannot inspect file");
            try (var input = files.open(root, 0)) {
                verify("cat\n".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            return new AgentFS.SearchPage(List.of(new AgentFS.SearchMatch(root, 0, 3)), null, true, List.of());
        };
        try (AgentFS custom = AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity("hdfs", List.of("supergroup")).searchProvider(provider).open()) {
            AgentFS.SearchPage page = custom.search("cat", path, 10, null);
            if (!called[0] || page.matches.size() != 1 || page.matches.get(0).offset != 0) {
                throw new AssertionError("Custom search provider was not used");
            }
        }
    }

    private static AgentFS client(String user, String group) throws IOException {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity(user, List.of(group)).open();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static void verify(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("File bytes differ");
        }
    }
}
