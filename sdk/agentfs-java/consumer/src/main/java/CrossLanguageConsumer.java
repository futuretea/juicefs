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
import io.juicefs.agentfs.AgentFS;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Run between independent Python processes against a synthetic path. */
public final class CrossLanguageConsumer {
    public static void main(String[] args) throws Exception {
        String path = System.getenv("AGENTFS_EXCHANGE_ROOT") + "/data";
        try (AgentFS fs = AgentFS.builder(System.getenv("AGENTFS_VOLUME"), System.getenv("AGENTFS_META"))
                .identity("hdfs", List.of("supergroup")).open()) {
            if (args.length != 1) throw new IllegalArgumentException("Expected exchange or verify");
            if (args[0].equals("exchange")) {
                check(fs, path, "python 猫 aa\n", "python 猫 ");
                fs.editFile(path, "python", "java");
                fs.applyPatch(path, patch(path, "java 猫 aa", "java 狗 aa"));
                check(fs, path, "java 狗 aa\n", "java 狗 ");
                fs.writeFile(System.getenv("AGENTFS_EXCHANGE_ROOT") + "/java-written",
                        "java created\n".getBytes(StandardCharsets.UTF_8));
            } else if (args[0].equals("verify")) {
                check(fs, path, "finished 犬 aa\n", "finished 犬 ");
            } else {
                throw new IllegalArgumentException("Expected exchange or verify");
            }
        }
        System.out.println("Cross-language Java " + args[0] + ": passed");
    }

    private static void check(AgentFS fs, String path, String expected, String prefix) throws Exception {
        if (!Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), fs.readFile(path, 0, 100).data)) {
            throw new AssertionError("Cross-language file bytes differ");
        }
        AgentFS.SearchPage page = fs.search("aa", path.substring(0, path.lastIndexOf('/')), 10, null);
        if (!page.complete || !page.skipped.isEmpty() || page.nextCursor != null || page.matches.size() != 1
                || !page.matches.get(0).path.equals(path)
                || page.matches.get(0).offset != prefix.getBytes(StandardCharsets.UTF_8).length) {
            throw new AssertionError("Cross-language byte offsets differ");
        }
    }

    private static String patch(String path, String before, String after) {
        return "*** Begin Patch\n*** Update File: " + path + "\n@@\n-" + before
                + "\n+" + after + "\n*** End Patch\n";
    }
}
