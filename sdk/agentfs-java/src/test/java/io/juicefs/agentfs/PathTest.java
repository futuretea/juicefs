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

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** U2/S19: fixed expected paths from the reviewed plan, not computed by the implementation. */
public class PathTest {
    @Test
    public void normalizeOrdinaryPathsWithoutDecodingLiteralCharacters() throws Exception {
        String[][] cases = {
            {"/a//b/", "/a/b"}, {"/a/./b", "/a/b"}, {"/a/../b", "/b"}, {"/../../b", "/../../b"},
            {"/a%20b", "/a%20b"}, {"/a b", "/a b"}, {"/a#b", "/a#b"},
            {"/a?b", "/a?b"}, {"/a\\b", "/a\\b"}, {"/中文", "/中文"}, {"/", "/"}
        };
        try (AgentFS fs = new FileOperationsTest.FakeNative().client()) {
            for (String[] entry : cases) {
                assertEquals(entry[0], entry[1], fs.readFile(entry[0], 0, 1).path);
                assertEquals(entry[0], entry[1], fs.writeFile(entry[0], new byte[0]).path);
            }
        }
    }

    @Test
    public void invalidPathsAreRejectedBeforeNativeCalls() throws Exception {
        FileOperationsTest.FakeNative nativeCalls = new FileOperationsTest.FakeNative();
        try (AgentFS fs = nativeCalls.client()) {
            for (String invalid : Arrays.asList(null, "", "relative", "jfs:///a", "//host/a", "/a\0b", "/\uD800")) {
                assertThrows(IllegalArgumentException.class, () -> fs.readFile(invalid, 0, 1));
                assertThrows(IllegalArgumentException.class, () -> fs.writeFile(invalid, new byte[0]));
            }
            assertEquals(0, nativeCalls.opens);
        }
    }
}
