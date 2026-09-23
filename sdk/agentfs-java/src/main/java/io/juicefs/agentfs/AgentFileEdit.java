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
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Private storage boundary and bounded rewrite shared by both editing operations. */
final class AgentFileEdit {
  interface Storage {
    EditFileInfo stat(String path) throws IOException;
    List<String> xattrs(String path) throws IOException;
    void access(String path) throws IOException;
    InputStream open(String path) throws IOException;
    OutputStream create(String path) throws IOException;
    void preserve(String path, EditFileInfo original) throws IOException;
    void commit(String source, String target) throws IOException;
    void delete(String path) throws IOException;
  }

  static final class Span {
    final long start;
    final byte[] before, after;
    Span(long start, byte[] before, byte[] after) {
      this.start = start;
      this.before = before;
      this.after = after;
    }
  }

  private final Storage storage;
  AgentFileEdit(Storage storage) { this.storage = storage; }

  AgentFS.EditResult editFile(String value, String oldText, String newText) throws IOException {
    String path = path(value);
    byte[] before = EditMatcher.utf8(oldText, "invalid_input");
    byte[] after = EditMatcher.utf8(newText, "invalid_input");
    if (before.length == 0) throw new EditException("invalid_input", "unchanged", "input", null);
    try {
      EditFileInfo info = inspect(path);
      long start = EditMatcher.unique(storage, path, before, 0, info.size, false);
      return finish(path, info, Collections.singletonList(new Span(start, before, after)));
    } catch (EditException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new EditException("storage_error", "unchanged", "read", e);
    }
  }

  AgentFS.EditResult applyPatch(String value, String patch) throws IOException {
    String path = path(value);
    List<ContentPatch.Block> blocks = ContentPatch.parse(path, patch);
    try {
      EditFileInfo info = inspect(path);
      return finish(path, info, ContentPatch.locate(storage, path, info, blocks));
    } catch (EditException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new EditException("storage_error", "unchanged", "read", e);
    }
  }

  private static String path(String value) throws EditException {
    EditMatcher.utf8(value, "invalid_input");
    try {
      return Inputs.path(value);
    } catch (IllegalArgumentException e) {
      throw new EditException("invalid_input", "unchanged", "input", e);
    }
  }

  private EditFileInfo inspect(String path) throws IOException {
    EditFileInfo info = storage.stat(path);
    checkObject(path, info);
    try {
      storage.access(path);
    } catch (IOException e) {
      throw new EditException("storage_error", "unchanged", "access", e);
    }
    return info;
  }

  private void checkObject(String path, EditFileInfo info) throws IOException {
    if ((info.mode & 0170000) != 0100000 || info.nlink != 1 || !storage.xattrs(path).isEmpty()) {
      throw new EditException("unsupported", "unchanged", "attributes", null);
    }
  }

  private AgentFS.EditResult finish(String path, EditFileInfo info, List<Span> spans) throws IOException {
    long size = info.size;
    int count = 0;
    for (Span span : spans) {
      size += (long) span.after.length - span.before.length;
      if (!Arrays.equals(span.before, span.after)) count++;
    }
    if (count == 0 || (size == info.size && unchanged(path, spans))) {
      return new AgentFS.EditResult(path, 0, 0);
    }
    String temporary = path.substring(0, path.lastIndexOf('/') + 1) + ".agentfs-edit-" + UUID.randomUUID();
    String stage = "create";
    boolean created = false;
    try {
      OutputStream output = storage.create(temporary);
      created = true;
      try (OutputStream closeable = output) {
        stage = "attributes";
        checkObject(temporary, storage.stat(temporary));
        stage = "write";
        rewrite(path, spans, closeable);
        stage = "close";
      }
      stage = "preserve";
      storage.preserve(temporary, info);
      EditFileInfo staged = storage.stat(temporary);
      checkObject(temporary, staged);
      if (staged.uid != info.uid || staged.gid != info.gid || (staged.mode & 07777) != (info.mode & 07777)
          || staged.size != size) throw new IOException("staged metadata verification failed");
      stage = "commit";
      storage.commit(temporary, path);
      return new AgentFS.EditResult(path, count, size);
    } catch (IOException | RuntimeException cause) {
      EditException failure = cause instanceof EditException ? (EditException) cause
          : new EditException(stage.equals("commit") ? "commit_unknown" : "storage_error",
              stage.equals("commit") ? "unknown" : "unchanged", stage, cause);
      if (created && failure.outcome.equals("unknown")) {
        failure.addSuppressed(new IOException("temporary path after unknown commit: " + temporary));
      } else if (created) {
        try {
          storage.delete(temporary);
        } catch (IOException | RuntimeException cleanup) {
          failure.addSuppressed(new IOException("temporary cleanup failed: " + temporary, cleanup));
        }
      }
      throw failure;
    }
  }

  private void rewrite(String path, List<Span> spans, OutputStream output) throws IOException {
    try (InputStream input = storage.open(path)) {
      long position = 0;
      for (Span span : spans) {
        copy(input, output, span.start - position);
        EditMatcher.skip(input, span.before.length);
        output.write(span.after);
        position = span.start + span.before.length;
      }
      copy(input, output, Long.MAX_VALUE);
    }
  }

  private static void copy(InputStream input, OutputStream output, long length) throws IOException {
    byte[] buffer = new byte[EditMatcher.BUFFER_SIZE];
    while (length > 0) {
      int read = input.read(buffer, 0, (int) Math.min(buffer.length, length));
      if (read < 0) {
        if (length != Long.MAX_VALUE) throw new IOException("source ended during rewrite");
        return;
      }
      if (read == 0) throw new IOException("read made no progress");
      output.write(buffer, 0, read);
      if (length != Long.MAX_VALUE) length -= read;
    }
  }

  private boolean unchanged(String path, List<Span> spans) throws IOException {
    try (InputStream original = storage.open(path)) {
      try {
        rewrite(path, spans, new OutputStream() {
          private final byte[] buffer = new byte[EditMatcher.BUFFER_SIZE];
          @Override public void write(int value) throws IOException {
            if (original.read() != (value & 255)) throw new Different();
          }
          @Override public void write(byte[] data, int offset, int length) throws IOException {
            while (length > 0) {
              int count = original.read(buffer, 0, Math.min(buffer.length, length));
              if (count == 0) throw new IOException("read made no progress");
              if (count < 0) throw new Different();
              for (int i = 0; i < count; i++) if (data[offset + i] != buffer[i]) throw new Different();
              offset += count;
              length -= count;
            }
          }
        });
        return original.read() < 0;
      } catch (Different e) {
        return false;
      }
    }
  }

  private static final class Different extends IOException { }
}
