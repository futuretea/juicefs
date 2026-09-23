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

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** External fixture preparation owns permissions; tests never manufacture a root-only substitute. */
public class NativeEditPermissionIT {
    @Test
    public void s14s15AllowedNonRootEditPreservesAttributesAndNoopKeepsInode() throws Exception {
        String path = required("AGENTFS_PERMISSION_ROOT") + "/allowed/data";
        try (AgentFS fs = client("agentfs-editor", "agentfs-editors");
             NativeClient nativeClient = nativeClient("agentfs-editor", "agentfs-editors")) {
            AgentFileEdit.Storage storage = nativeClient.editStorage();
            EditFileInfo before = storage.stat(path);
            assertTrue("Fixture must have a non-root owner", before.uid != 0);
            assertEquals(0640, before.mode & 07777);
            assertContent(fs, path, "cat\n");
            fs.editFile(path, "cat", "dog");
            EditFileInfo edited = storage.stat(path);
            assertAttributes(before, edited);
            assertTrue(before.inode != edited.inode);
            assertContent(fs, path, "dog\n");
            AgentFS.EditResult noop = fs.editFile(path, "dog", "dog");
            assertEquals(0, noop.replacements);
            assertEquals(0, noop.bytesWritten);
            assertEquals(edited.inode, storage.stat(path).inode);
            assertAttributes(edited, storage.stat(path));
            fs.applyPatch(path, patch(path, "dog", "cat"));
            assertAttributes(before, storage.stat(path));
            assertContent(fs, path, "cat\n");
        }
    }

    @Test
    public void s3s15FileParentAndStickyPermissionsDenyBothEditingOperations() throws Exception {
        String root = required("AGENTFS_PERMISSION_ROOT");
        try (AgentFS owner = client("hdfs", "supergroup");
             AgentFS editor = client("agentfs-editor", "agentfs-editors");
             NativeClient nativeOwner = nativeClient("hdfs", "supergroup")) {
            for (String name : List.of("denied/data", "parent-denied/data", "sticky/data")) {
                String path = root + "/" + name;
                EditFileInfo before = nativeOwner.editStorage().stat(path);
                assertContent(owner, path, "cat\n");
                for (boolean usePatch : new boolean[] {false, true}) {
                    EditException error = assertThrows(EditException.class, () -> {
                        if (usePatch) editor.applyPatch(path, patch(path, "cat", "dog"));
                        else editor.editFile(path, "cat", "dog");
                    });
                    assertEquals(name, "storage_error", error.code);
                    assertEquals("unchanged", error.outcome);
                    EditFileInfo after = nativeOwner.editStorage().stat(path);
                    assertEquals(before.inode, after.inode);
                    assertAttributes(before, after);
                    assertContent(owner, path, "cat\n");
                }
            }
        }
    }

    @Test
    public void unsupportedLinksAndAttributesRemainUnchangedEvenForSuperuser() throws Exception {
        String root = required("AGENTFS_PERMISSION_ROOT");
        try (AgentFS fs = client("hdfs", "supergroup");
             NativeClient nativeOwner = nativeClient("hdfs", "supergroup")) {
            for (String name : List.of("hardlink", "symlink", "xattr")) {
                String path = root + "/" + name;
                EditFileInfo before = nativeOwner.editStorage().stat(path);
                for (boolean usePatch : new boolean[] {false, true}) {
                    EditException error = assertThrows(EditException.class, () -> {
                        if (usePatch) fs.applyPatch(path, patch(path, "cat", "dog"));
                        else fs.editFile(path, "cat", "dog");
                    });
                    assertEquals(name, "unsupported", error.code);
                    assertEquals("unchanged", error.outcome);
                    EditFileInfo after = nativeOwner.editStorage().stat(path);
                    assertEquals(before.inode, after.inode);
                    assertAttributes(before, after);
                    assertContent(fs, path, "cat\n");
                }
            }
        }
    }

    static String patch(String path, String oldText, String newText) {
        return "*** Begin Patch\n*** Update File: " + path + "\n@@\n-" + oldText
                + "\n+" + newText + "\n*** End Patch\n";
    }

    static void assertAttributes(EditFileInfo before, EditFileInfo after) {
        assertEquals(before.uid, after.uid);
        assertEquals(before.gid, after.gid);
        assertEquals(before.mode, after.mode);
        assertEquals(before.nlink, after.nlink);
    }

    static void assertContent(AgentFS fs, String path, String expected) throws Exception {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), fs.readFile(path, 0, 100).data);
    }

    static AgentFS client(String user, String group) throws Exception {
        return AgentFS.builder(required("AGENTFS_VOLUME"), required("AGENTFS_META"))
                .identity(user, List.of(group)).open();
    }

    static NativeClient nativeClient(String user, String group) throws Exception {
        return new NativeClient(required("AGENTFS_VOLUME"), required("AGENTFS_META"),
                user, group, "hdfs", "supergroup");
    }

    static String required(String name) {
        String value = System.getenv(name);
        assertNotNull("Required integration environment is absent: " + name, value);
        assertFalse("Required integration environment is empty: " + name, value.isEmpty());
        return value;
    }
}
