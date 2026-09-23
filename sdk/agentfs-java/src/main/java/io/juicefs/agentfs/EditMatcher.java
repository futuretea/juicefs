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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded byte scanning; patterns use KMP, including overlapping occurrences. */
final class EditMatcher {
  static final int BUFFER_SIZE = 65536;

  static byte[] utf8(String value, String code) throws EditException {
    if (value == null) throw new EditException(code, "unchanged", "input", null);
    try {
      ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException e) {
      throw new EditException(code, "unchanged", "input", e);
    }
  }

  static void skip(InputStream input, long length) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    while (length > 0) {
      int read = input.read(buffer, 0, (int) Math.min(buffer.length, length));
      if (read <= 0) throw new IOException("source ended or made no progress");
      length -= read;
    }
  }

  static long unique(AgentFileEdit.Storage storage, String path, byte[] pattern,
                     long start, long size, boolean eof) throws IOException {
    int[] prefix = new int[pattern.length];
    for (int i = 1, j = 0; i < pattern.length; i++) {
      while (j > 0 && pattern[i] != pattern[j]) j = prefix[j - 1];
      if (pattern[i] == pattern[j]) j++;
      prefix[i] = j;
    }
    long found = -1, position = start;
    int matched = 0;
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream input = storage.open(path)) {
      skip(input, start);
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read == 0) throw new IOException("read made no progress");
        for (int i = 0; i < read; i++, position++) {
          while (matched > 0 && buffer[i] != pattern[matched]) matched = prefix[matched - 1];
          if (buffer[i] == pattern[matched]) matched++;
          if (matched == pattern.length) {
            if (!eof || position + 1 == size) {
              if (found >= 0) throw failure("ambiguous_match");
              found = position + 1 - pattern.length;
            }
            matched = prefix[matched - 1];
          }
        }
      }
      if (position < size) throw new IOException("source ended before its declared length");
    }
    if (found < 0) throw failure("no_match");
    return found;
  }

  static long anchor(AgentFileEdit.Storage storage, String path, byte[] anchor, long start)
      throws IOException {
    // Only retain a potential anchor line, never an unbounded source line.
    byte[] line = new byte[anchor.length + 1];
    byte[] buffer = new byte[BUFFER_SIZE];
    long position = 0, lineStart = 0, lineLength = 0, found = -1;
    try (InputStream input = storage.open(path)) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read == 0) throw new IOException("read made no progress");
        for (int i = 0; i < read; i++) {
          byte value = buffer[i];
          position++;
          if (value == '\n') {
            long length = lineLength;
            if (length > 0 && length <= line.length && line[(int) length - 1] == '\r') length--;
            if (lineStart >= start && sameLine(line, length, anchor)) {
              if (found >= 0) throw failure("ambiguous_match");
              found = position;
            }
            lineStart = position;
            lineLength = 0;
          } else {
            if (lineLength < line.length) line[(int) lineLength] = value;
            lineLength++;
          }
        }
      }
    }
    if (lineLength > 0 && lineStart >= start && sameLine(line, lineLength, anchor)) {
      if (found >= 0) throw failure("ambiguous_match");
      found = position;
    }
    if (found < 0) throw failure("no_match");
    return found;
  }

  private static boolean sameLine(byte[] line, long length, byte[] anchor) {
    if (length != anchor.length) return false;
    for (int i = 0; i < anchor.length; i++) if (line[i] != anchor[i]) return false;
    return true;
  }

  static boolean endsWithLf(AgentFileEdit.Storage storage, String path) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int last = -1;
    try (InputStream input = storage.open(path)) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read == 0) throw new IOException("read made no progress");
        last = buffer[read - 1];
      }
    }
    return last == '\n';
  }

  static EditException failure(String code) {
    return new EditException(code, "unchanged", "match", null);
  }
}
