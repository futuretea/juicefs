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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** V4 public native editing; permission and attribute fixtures are verified separately. */
public class NativeEditIT {
    @Test
    public void s11All50FixturesThroughPublicNativeEditAndPatch() throws Exception {
        JSONArray cases;
        try (InputStream input = new java.io.FileInputStream(EditTest.sharedFixtures())) {
            cases = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("cases");
        }
        assertEquals(50, cases.length());
        String root = required("AGENTFS_EDIT_ROOT") + "/fixtures";
        try (AgentFS fs = client()) {
            for (int index = 0; index < cases.length(); index++) {
                JSONObject item = cases.getJSONObject(index);
                String path = root + "/case-" + index;
                String id = item.getString("id");
                byte[] original = Base64.getDecoder().decode(item.getString("input_b64"));
                fs.writeFile(path, original);
                try {
                    AgentFS.EditResult result = item.getString("operation").equals("edit")
                            ? fs.editFile(path, item.getString("old_text"), item.getString("new_text"))
                            : fs.applyPatch(path, item.getString("patch").replace("Update File: /a\n", "Update File: " + path + "\n"));
                    assertFalse(id, item.has("error"));
                    byte[] expected = Base64.getDecoder().decode(item.getString("expected_b64"));
                    assertArrayEquals(id, expected, fs.readFile(path, 0, expected.length + 1).data);
                    assertEquals(id, item.getInt("replacements"), result.replacements);
                    assertEquals(id, result.replacements == 0 ? 0 : expected.length, result.bytesWritten);
                } catch (EditException error) {
                    assertTrue(id + ": " + error.code, item.has("error"));
                    assertEquals(id, item.getJSONObject("error").getString("code"), error.code);
                    assertEquals(id, "unchanged", error.outcome);
                    assertArrayEquals(id, original, fs.readFile(path, 0, original.length + 1).data);
                }
            }
        }
    }

    @Test
    public void s10DeletingFinalLinePreservesUnmodifiedNewline() throws Exception {
        String path = required("AGENTFS_EDIT_ROOT") + "/final-line";
        try (AgentFS fs = client()) {
            fs.writeFile(path, "cat\ndog".getBytes(StandardCharsets.UTF_8));
            fs.applyPatch(path, "*** Begin Patch\n*** Update File: " + path
                    + "\n@@\n cat\n-dog\n*** End of File\n*** End Patch\n");
            assertArrayEquals("cat\n".getBytes(StandardCharsets.UTF_8), fs.readFile(path, 0, 100).data);
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
