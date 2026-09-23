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

import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Base64;

public class EditTest extends TestCase {
  static java.io.File sharedFixtures() {
    String property = System.getProperty("agentfs.edit.fixtures");
    if (property != null) return new java.io.File(property);
    // Surefire forks with the module directory as working directory; the second
    // candidate keeps the canonical fixture reachable from target/test-classes.
    for (String candidate : new String[]{"../agentfs/fixtures/edit_cases.json",
        "../../../agentfs/fixtures/edit_cases.json"}) {
      java.io.File file = new java.io.File(candidate);
      if (file.isFile()) return file;
    }
    return new java.io.File("../agentfs/fixtures/edit_cases.json");
  }

  public void testAll50SharedFixturesThroughEditAlgorithm() throws Exception {
    org.json.JSONArray cases;
    try (InputStream input = new java.io.FileInputStream(sharedFixtures())) {
      cases = new org.json.JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getJSONArray("cases");
    }
    assertEquals("Do not silently omit shared fixtures", 50, cases.length());
    for (int index = 0; index < cases.length(); index++) {
      org.json.JSONObject item = cases.getJSONObject(index);
      LocalStorage storage = new LocalStorage();
      try {
        byte[] original = Base64.getDecoder().decode(item.getString("input_b64"));
        Files.write(storage.target, original);
        AgentFileEdit editor = new AgentFileEdit(storage);
        try {
          AgentFS.EditResult result = item.getString("operation").equals("edit")
              ? editor.editFile("/a", item.getString("old_text"), item.getString("new_text"))
              : editor.applyPatch("/a", item.getString("patch"));
          assertFalse(item.getString("id"), item.has("error"));
          byte[] expected = Base64.getDecoder().decode(item.getString("expected_b64"));
          assertTrue(item.getString("id"), java.util.Arrays.equals(expected, Files.readAllBytes(storage.target)));
          assertEquals(item.getString("id"), item.getInt("replacements"), result.replacements);
          assertEquals(result.replacements == 0 ? 0 : expected.length, result.bytesWritten);
          if (result.replacements == 0) assertEquals(0, storage.commits);
        } catch (EditException failure) {
          assertTrue(item.getString("id") + ": " + failure.code, item.has("error"));
          assertEquals(item.getString("id"), item.getJSONObject("error").getString("code"), failure.code);
          assertEquals("unchanged", failure.outcome);
          assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(storage.target)));
          assertEquals(0, storage.commits);
        }
      } finally {
        storage.close();
      }
    }
  }

  public void testStorageFailuresAndCommitUncertainty() throws Exception {
      for (String operation : new String[]{"edit", "patch"}) {
        for (String fault : new String[]{"access", "create", "write", "close", "preserve", "reject", "unknown", "cleanup"}) {
          LocalStorage storage = new LocalStorage();
          try {
            Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
            storage.fault = fault;
            AgentFileEdit agent = new AgentFileEdit(storage);
            try {
              if (operation.equals("edit")) agent.editFile("/a", "cat", "dog");
              else agent.applyPatch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n");
              fail(fault);
            } catch (EditException error) {
              boolean unknown = fault.equals("unknown");
              assertEquals(fault, unknown ? "commit_unknown" : "storage_error", error.code);
              assertEquals(fault, unknown ? "unknown" : "unchanged", error.outcome);
              assertEquals(fault, unknown ? "dog\n" : "cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
              assertEquals(unknown ? 1 : 0, storage.commits);
              assertNotNull(error.stage);
            }
          } finally {
            storage.close();
          }
        }
      }
  }

  public void testInvalidUnicodeAndLongPatterns() throws Exception {
    LocalStorage storage = new LocalStorage();
    try {
      Files.write(storage.target, "cat".getBytes(StandardCharsets.UTF_8));
      AgentFileEdit agent = new AgentFileEdit(storage);
      for (char surrogate : new char[]{(char) 0xd800, (char) 0xdc00}) {
        String text = String.valueOf(surrogate);
        for (int position = 0; position < 3; position++) {
          try {
            if (position == 0) agent.editFile("/a", text, "dog");
            else if (position == 1) agent.editFile("/a", "cat", text);
            else agent.applyPatch("/a", text);
            fail("invalid Unicode must be rejected");
          } catch (EditException error) {
            assertEquals("invalid_input", error.code);
            assertEquals("unchanged", error.outcome);
          }
        }
      }
      assertEquals("cat", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
      assertEquals(0, storage.commits);
      byte[] prefix = new byte[(1 << 20) - 3];
      java.util.Arrays.fill(prefix, (byte) 'x');
      char[] longText = new char[(1 << 20) + 3];
      java.util.Arrays.fill(longText, 'a');
      for (String old : new String[]{"target", new String(longText) + "b"}) {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        data.write(prefix);
        data.write(old.getBytes(StandardCharsets.UTF_8));
        data.write(255);
        Files.write(storage.target, data.toByteArray());
        agent.editFile("/a", old, "dog");
        data.reset();
        data.write(prefix);
        data.write("dog".getBytes(StandardCharsets.UTF_8));
        data.write(255);
        assertTrue(java.util.Arrays.equals(data.toByteArray(), Files.readAllBytes(storage.target)));
      }
    } finally {
      storage.close();
    }
  }

  public void testUnsupportedObjectLeavesBothEntriesUnchanged() throws Exception {
      for (String kind : new String[]{"symlink", "hardlink", "xattr", "temp_xattr"}) {
        for (boolean patch : new boolean[]{false, true}) {
          LocalStorage storage = new LocalStorage() {
            public EditFileInfo stat(String path) throws IOException {
              return new EditFileInfo(1, kind.equals("hardlink") ? 2 : 1, 1000, 1000,
                  kind.equals("symlink") ? 0120777 : 0100640, Files.size(local(path)), "owner", "group");
            }
            public List<String> xattrs(String path) {
              return kind.equals("xattr") || (kind.equals("temp_xattr") && !path.substring(path.lastIndexOf('/') + 1).equals("a"))
                  ? Collections.singletonList("user.synthetic") : Collections.emptyList();
            }
          };
          try {
            Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
            AgentFileEdit agent = new AgentFileEdit(storage);
            try {
              if (patch) agent.applyPatch("/a", "*** Begin Patch\n*** Update File: /a\n@@\n-cat\n+dog\n*** End Patch\n");
              else agent.editFile("/a", "cat", "dog");
              fail(kind);
            } catch (EditException error) {
              assertEquals(kind, "unsupported", error.code);
              assertEquals("unchanged", error.outcome);
              assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
              assertEquals(0, storage.commits);
            }
          } finally {
            storage.close();
          }
        }
      }
  }

  public void testUnknownCommitRetainsUnconsumedTemporaryFile() throws Exception {
    final int[] calls = new int[2];
    final String[] pending = new String[1];
    LocalStorage storage = new LocalStorage() {
      public void commit(String source, String target) throws IOException {
        calls[0]++;
        pending[0] = source;
        throw new IOException("synthetic lost commit response before observable effect");
      }
      public void delete(String path) throws IOException {
        calls[1]++;
        super.delete(path);
      }
    };
    try {
      Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
      AgentFileEdit agent = new AgentFileEdit(storage);
      try {
        agent.editFile("/a", "cat", "dog");
        fail("commit outcome must be unknown");
      } catch (EditException error) {
        assertEquals("commit_unknown", error.code);
        assertEquals("unknown", error.outcome);
        assertEquals(1, calls[0]);
        assertEquals("unknown commit must not clean up its source", 0, calls[1]);
        assertTrue(Files.exists(storage.local(pending[0])));
        assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
      }
    } finally {
      storage.close();
    }
  }

  public void testEmbeddedNulPathRejectedBeforeStorage() throws Exception {
    LocalStorage storage = new LocalStorage();
    try {
      Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
      AgentFileEdit agent = new AgentFileEdit(storage);
      String path = "/a" + (char) 0 + "suffix";
      for (boolean patch : new boolean[]{false, true}) {
        try {
          if (patch) agent.applyPatch(path, "*** Begin Patch\n*** Update File: " + path + "\n@@\n-cat\n+dog\n*** End Patch\n");
          else agent.editFile(path, "cat", "dog");
          fail("NUL must be rejected before passing a native path");
        } catch (EditException error) {
          assertEquals("invalid_input", error.code);
          assertEquals("unchanged", error.outcome);
        }
      }
      assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
      assertEquals(0, storage.commits);
    } finally {
      storage.close();
    }
  }

  public void testShortReadsAndZeroProgress() throws Exception {
      for (boolean zero : new boolean[]{false, true}) {
        LocalStorage storage = new LocalStorage() {
          public InputStream open(String path) throws IOException {
            return new java.io.FilterInputStream(super.open(path)) {
              public int read(byte[] data, int offset, int length) throws IOException {
                return zero ? 0 : in.read(data, offset, Math.min(2, length));
              }
            };
          }
        };
        try {
          Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
          AgentFileEdit agent = new AgentFileEdit(storage);
          try {
            agent.editFile("/a", "cat", "dog");
            assertFalse("zero-progress read succeeded", zero);
            assertEquals("dog\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
          } catch (EditException error) {
            assertTrue(zero);
            assertEquals("storage_error", error.code);
            assertEquals("unchanged", error.outcome);
            assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
            assertEquals(0, storage.commits);
          }
        } finally {
          storage.close();
        }
      }
  }

  public void testCleanupFailurePreservesPrimaryCauseAndTemporaryPath() throws Exception {
    LocalStorage storage = new LocalStorage();
    try {
      Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
      storage.fault = "cleanup";
      try {
        new AgentFileEdit(storage).editFile("/a", "cat", "dog");
        fail("injected write failure must be reported");
      } catch (EditException error) {
        assertEquals("storage_error", error.code);
        assertEquals("synthetic primary failure", error.getCause().getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertTrue(error.getSuppressed()[0].getMessage().contains(".agentfs-edit-"));
        assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
        assertEquals(0, storage.commits);
      }
    } finally {
      storage.close();
    }
  }

  public void testPrematureEofReportsStorageFailure() throws Exception {
    LocalStorage storage = new LocalStorage() {
      public InputStream open(String path) { return new java.io.ByteArrayInputStream(new byte[]{'x'}); }
    };
    try {
      Files.write(storage.target, "cat\n".getBytes(StandardCharsets.UTF_8));
      try {
        new AgentFileEdit(storage).editFile("/a", "dog", "fox");
        fail("stream ended before its declared size");
      } catch (EditException error) {
        assertEquals("storage_error", error.code);
        assertEquals("unchanged", error.outcome);
        assertEquals(0, storage.commits);
        assertEquals("cat\n", new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
      }
    } finally {
      storage.close();
    }
  }

  public void testDeletingFinalLineKeepsContextNewlineWithAndWithoutContext() throws Exception {
    for (String newline : new String[]{"\n", "\r\n"}) {
      for (boolean includeContext : new boolean[]{false, true}) {
        LocalStorage storage = new LocalStorage();
        try {
          Files.write(storage.target, ("cat" + newline + "dog").getBytes(StandardCharsets.UTF_8));
          String patch = "*** Begin Patch\n*** Update File: /a\n@@\n"
              + (includeContext ? " cat" + newline : "") + "-dog" + newline
              + "*** End of File\n*** End Patch\n";
          AgentFS.EditResult result = new AgentFileEdit(storage).applyPatch("/a", patch);
          assertEquals("cat" + newline, new String(Files.readAllBytes(storage.target), StandardCharsets.UTF_8));
          assertEquals(1, result.replacements);
          assertEquals(1, storage.commits);
        } finally {
          storage.close();
        }
      }
    }
  }

  private static class LocalStorage implements AgentFileEdit.Storage {
    final java.nio.file.Path root = Files.createTempDirectory("agentfs-storage-");
    final java.nio.file.Path target = root.resolve("a");
    String fault = "";
    int commits;

    LocalStorage() throws IOException { }
    java.nio.file.Path local(String path) { return root.resolve(path.substring(path.lastIndexOf('/') + 1)); }
    public EditFileInfo stat(String path) throws IOException {
      return new EditFileInfo(1, 1, 1000, 1000, 0100640, Files.size(local(path)), "owner", "group");
    }
    public List<String> xattrs(String path) { return Collections.emptyList(); }
    public void access(String path) throws IOException { trip("access"); }
    public InputStream open(String path) throws IOException { return Files.newInputStream(local(path)); }
    public OutputStream create(String path) throws IOException {
      trip("create");
      OutputStream stream = Files.newOutputStream(local(path), java.nio.file.StandardOpenOption.CREATE_NEW);
      return new java.io.FilterOutputStream(stream) {
        public void write(byte[] data, int offset, int length) throws IOException {
          trip("write");
          if (fault.equals("cleanup")) throw new IOException("synthetic primary failure");
          out.write(data, offset, length);
        }
        public void close() throws IOException { super.close(); trip("close"); }
      };
    }
    public void preserve(String path, EditFileInfo original) throws IOException { trip("preserve"); }
    public void commit(String source, String dest) throws IOException {
      if (fault.equals("reject")) throw new EditException("storage_error", "unchanged", "commit", new IOException("synthetic refusal"));
      commits++;
      Files.move(local(source), local(dest), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      trip("unknown");
    }
    public void delete(String path) throws IOException { trip("cleanup"); Files.deleteIfExists(local(path)); }
    private void trip(String stage) throws IOException { if (fault.equals(stage)) throw new IOException("synthetic " + stage); }
    void close() throws IOException {
      try (java.nio.file.DirectoryStream<java.nio.file.Path> entries = Files.newDirectoryStream(root)) {
        for (java.nio.file.Path entry : entries) Files.delete(entry);
      }
      Files.delete(root);
    }
  }

}
