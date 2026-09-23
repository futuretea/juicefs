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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The deliberately small single-file context patch protocol. */
final class ContentPatch {
  static final class Block {
    final byte[] anchor, before, after;
    final boolean eof;
    final boolean preserveAfterEnding;
    Block(byte[] anchor, byte[] before, byte[] after, boolean eof, boolean preserveAfterEnding) {
      this.anchor = anchor;
      this.before = before;
      this.after = after;
      this.eof = eof;
      this.preserveAfterEnding = preserveAfterEnding;
    }
  }

  static List<Block> parse(String target, String patch) throws IOException {
    EditMatcher.utf8(patch, "invalid_input");
    String[] lines = patch.split("(?<=\n)");
    if (lines.length < 4 || !control(lines[0]).equals("*** Begin Patch")
        || !control(lines[1]).startsWith("*** Update File: ")
        || !control(lines[lines.length - 1]).equals("*** End Patch")) throw invalid();
    String name = control(lines[1]).substring("*** Update File: ".length());
    try {
      if (!Inputs.path(name).equals(target)) throw invalid();
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    List<Block> blocks = new ArrayList<Block>();
    int index = 2;
    while (index < lines.length - 1) {
      String header = control(lines[index++]);
      if (!header.equals("@@") && (!header.startsWith("@@ ") || header.length() == 3)) throw invalid();
      if (header.matches("@@ -[0-9]+(?:,[0-9]+)? \\+[0-9]+(?:,[0-9]+)? @@.*")) throw invalid();
      byte[] anchor = header.equals("@@") ? null : EditMatcher.utf8(header.substring(3), "invalid_patch");
      StringBuilder before = new StringBuilder(), after = new StringBuilder();
      boolean changed = false, eof = false;
      int lastOutputContextEnd = -1;
      while (index < lines.length - 1 && !control(lines[index]).startsWith("@@")) {
        String line = lines[index++];
        if (control(line).equals("*** End of File")) {
          eof = true;
          if (index != lines.length - 1) throw invalid();
          break;
        }
        if (line.isEmpty() || !line.endsWith("\n")) throw invalid();
        char kind = line.charAt(0);
        if (kind != ' ' && kind != '-' && kind != '+') throw invalid();
        if (kind != '+') before.append(line.substring(1));
        if (kind != '-') {
          after.append(line.substring(1));
          lastOutputContextEnd = kind == ' ' ? before.length() : -1;
        }
        changed |= kind != ' ';
      }
      if (!changed || (before.length() == 0 && !eof)) throw invalid();
      blocks.add(new Block(anchor, EditMatcher.utf8(before.toString(), "invalid_patch"),
          EditMatcher.utf8(after.toString(), "invalid_patch"), eof,
          lastOutputContextEnd >= 0 && lastOutputContextEnd < before.length()));
    }
    if (blocks.isEmpty()) throw invalid();
    return blocks;
  }

  static List<AgentFileEdit.Span> locate(AgentFileEdit.Storage storage, String path,
                                        EditFileInfo info, List<Block> blocks) throws IOException {
    List<AgentFileEdit.Span> result = new ArrayList<AgentFileEdit.Span>();
    long cursor = 0;
    boolean newline = !blocks.get(blocks.size() - 1).eof || EditMatcher.endsWithLf(storage, path);
    for (Block block : blocks) {
      byte[] before = block.eof && !newline ? withoutEnding(block.before) : block.before;
      byte[] after = block.eof && !newline && !block.preserveAfterEnding
          ? withoutEnding(block.after) : block.after;
      if (block.anchor != null) cursor = EditMatcher.anchor(storage, path, block.anchor, cursor);
      long offset;
      if (before.length == 0) {
        if (info.size != 0 || blocks.size() != 1 || !block.eof || block.anchor != null) throw invalid();
        offset = 0;
      } else {
        offset = EditMatcher.unique(storage, path, before, cursor, info.size, block.eof);
      }
      result.add(new AgentFileEdit.Span(offset, before, after));
      cursor = offset + before.length;
    }
    return result;
  }

  private static byte[] withoutEnding(byte[] data) {
    int length = data.length;
    if (length > 0 && data[length - 1] == '\n') {
      length--;
      if (length > 0 && data[length - 1] == '\r') length--;
    }
    return Arrays.copyOf(data, length);
  }

  private static String control(String line) {
    int length = line.length();
    if (length > 0 && line.charAt(length - 1) == '\n') {
      length--;
      if (length > 0 && line.charAt(length - 1) == '\r') length--;
    }
    return line.substring(0, length);
  }

  private static EditException invalid() {
    return new EditException("invalid_patch", "unchanged", "parse", null);
  }
}
