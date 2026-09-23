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

package io.juicefs.agentfs;


import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ExactSearch {
  private static final int READ_SIZE = 1 << 20;
  private final String cursorVersion;
  private final SearchFiles fileSystem;
  private final Matcher matcher;

  interface Matcher {
    List<Integer> find(byte[] window, byte[] needle, long minimum, int limit);
  }

  ExactSearch(SearchFiles fileSystem, String provider, Matcher matcher) {
    this.fileSystem = fileSystem;
    this.cursorVersion = "v1-" + provider;
    this.matcher = matcher;
  }

  AgentFS.SearchPage search(String query, String rootValue, int limit, String cursor) throws IOException {
    byte[] needle = validateQuery(query, limit);
    String root = Inputs.path(rootValue);
    Cursor resume = decodeCursor(cursor, root, needle);
    Traversal traversal = collectFiles(root);
    if (resume != null && !traversal.contains(resume.path)) {
      traversal.skipped.add(new AgentFS.SearchSkip(resume.path, "cursor_path_missing"));
    }
    List<AgentFS.SearchMatch> matches = new ArrayList<AgentFS.SearchMatch>();
    for (FileRecord file : traversal.files) {
      int comparison = resume == null ? 1 : compareUtf8(file.path, resume.path);
      if (comparison < 0) {
        continue;
      }
      Long startAfter = comparison == 0 ? resume.offset : null;
      ScanResult scan = scanFile(file.path, needle, startAfter, limit - matches.size());
      if (scan.skip != null) {
        traversal.skipped.add(scan.skip);
        continue;
      }
      matches.addAll(scan.matches);
      if (scan.reachedLimit) {
        AgentFS.SearchMatch last = matches.get(matches.size() - 1);
        return page(matches, encodeCursor(root, needle, last), traversal.skipped);
      }
    }
    traversal.skipped.addAll(changedDirectories(traversal.directories));
    return page(matches, null, traversal.skipped);
  }

  private Traversal collectFiles(String root) {
    List<String> pending = new ArrayList<String>();
    pending.add(root);
    Traversal traversal = new Traversal();
    while (!pending.isEmpty()) {
      String directory = pending.remove(pending.size() - 1);
      SearchFiles.FileInfo before;
      try {
        before = fileSystem.stat(directory);
      } catch (IOException error) {
        traversal.skipped.add(new AgentFS.SearchSkip(directory, "list_error", errnoOf(error)));
        continue;
      }
      if (!"directory".equals(before.type)) {
        traversal.skipped.add(new AgentFS.SearchSkip(directory, "non_directory_root"));
        continue;
      }
      List<SearchFiles.FileInfo> entries;
      try {
        entries = fileSystem.list(directory);
      } catch (IOException error) {
        traversal.skipped.add(new AgentFS.SearchSkip(directory, "list_error", errnoOf(error)));
        continue;
      }
      traversal.directories.add(new DirectoryRecord(directory, stamp(before)));
      List<String> children = new ArrayList<String>();
      for (SearchFiles.FileInfo entry : entries) {
        String path = entry.path;
        if ("directory".equals(entry.type)) {
          children.add(entry.path);
        } else if ("file".equals(entry.type)) {
          traversal.files.add(new FileRecord(path));
        } else {
          traversal.skipped.add(new AgentFS.SearchSkip(path, "nonregular"));
        }
      }
      Collections.sort(children, new Comparator<String>() {
        @Override
        public int compare(String left, String right) {
          return compareUtf8(left, right);
        }
      });
      for (int index = children.size() - 1; index >= 0; index--) {
        pending.add(children.get(index));
      }
    }
    Collections.sort(traversal.files, new Comparator<FileRecord>() {
      @Override
      public int compare(FileRecord left, FileRecord right) {
        return compareUtf8(left.path, right.path);
      }
    });
    return traversal;
  }

  private ScanResult scanFile(String path, byte[] needle, Long startAfter, int remaining) {
    try {
      SearchFiles.FileInfo before = fileSystem.stat(path);
      if (!"file".equals(before.type)) {
        return ScanResult.skip(path, "nonregular");
      }
      ScanResult result = findMatches(path, needle, startAfter, remaining);
      SearchFiles.FileInfo after = fileSystem.stat(path);
      if (!stamp(before).equals(stamp(after))) {
        return ScanResult.skip(path, "changed");
      }
      return result;
    } catch (IOException error) {
      return ScanResult.skip(path, "read_error", errnoOf(error));
    }
  }

  private ScanResult findMatches(String path, byte[] needle, Long startAfter, int remaining) throws IOException {
    int overlap = Math.max(0, needle.length - 1);
    long start = startAfter == null ? 0 : Math.max(0, startAfter - overlap);
    List<AgentFS.SearchMatch> matches = new ArrayList<AgentFS.SearchMatch>();
    byte[] carry = new byte[0];
    long cursor = start;
    try (InputStream input = fileSystem.open(path, start)) {
      byte[] block = new byte[READ_SIZE];
      while (true) {
        int count = input.read(block);
        if (count < 0) {
          return ScanResult.matches(matches, false);
        }
        if (count == 0) {
          throw new IOException("read made no progress");
        }
        byte[] window = new byte[carry.length + count];
        System.arraycopy(carry, 0, window, 0, carry.length);
        System.arraycopy(block, 0, window, carry.length, count);
        long windowOffset = cursor - carry.length;
        long minimum = startAfter == null ? 0 : startAfter - windowOffset + 1;
        for (int found : matcher.find(window, needle, minimum, remaining - matches.size())) {
          long offset = windowOffset + found;
          if (startAfter == null || offset > startAfter) {
            matches.add(new AgentFS.SearchMatch(path, offset, needle.length));
            if (matches.size() == remaining) {
              return ScanResult.matches(matches, true);
            }
          }
        }
        carry = overlap == 0 ? new byte[0] : suffix(window, overlap);
        cursor += count;
      }
    }
  }

  private List<AgentFS.SearchSkip> changedDirectories(List<DirectoryRecord> directories) {
    List<AgentFS.SearchSkip> skipped = new ArrayList<AgentFS.SearchSkip>();
    for (DirectoryRecord directory : directories) {
      try {
        if (!directory.stamp.equals(stamp(fileSystem.stat(directory.path)))) {
          skipped.add(new AgentFS.SearchSkip(directory.path, "changed"));
        }
      } catch (IOException error) {
        skipped.add(new AgentFS.SearchSkip(directory.path, "changed", errnoOf(error)));
      }
    }
    return skipped;
  }

  private static Integer errnoOf(IOException error) {
    if (error instanceof NativeMetadata.NativeError) {
      return ((NativeMetadata.NativeError) error).errno;
    }
    return null;
  }

  private static byte[] validateQuery(String query, int limit) {
    if (query == null || query.isEmpty()) {
      throw new IllegalArgumentException("query must be a nonempty string");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be a positive integer");
    }
    try {
      ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(query));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      return result;
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("query must be valid UTF-8", error);
    }
  }

  private static AgentFS.SearchPage page(List<AgentFS.SearchMatch> matches, String nextCursor,
      List<AgentFS.SearchSkip> skipped) {
    return new AgentFS.SearchPage(matches, nextCursor, nextCursor == null && skipped.isEmpty(), skipped);
  }

  static int indexOf(byte[] source, byte[] needle, int from) {
    for (int index = from; index <= source.length - needle.length; index++) {
      int offset = 0;
      while (offset < needle.length && source[index + offset] == needle[offset]) {
        offset++;
      }
      if (offset == needle.length) {
        return index;
      }
    }
    return -1;
  }

  private static byte[] suffix(byte[] data, int limit) {
    int size = Math.min(data.length, limit);
    byte[] result = new byte[size];
    System.arraycopy(data, data.length - size, result, 0, size);
    return result;
  }

  static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int length = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < length; index++) {
      int comparison = (leftBytes[index] & 0xff) - (rightBytes[index] & 0xff);
      if (comparison != 0) {
        return comparison;
      }
    }
    return leftBytes.length - rightBytes.length;
  }

  private String encodeCursor(String root, byte[] needle, AgentFS.SearchMatch match) {
    String path = Base64.getUrlEncoder().withoutPadding().encodeToString(match.path.getBytes(StandardCharsets.UTF_8));
    return cursorVersion + "." + digest(root.getBytes(StandardCharsets.UTF_8)) + "." + digest(needle)
        + "." + path + "." + match.offset;
  }

  private Cursor decodeCursor(String cursor, String root, byte[] needle) {
    if (cursor == null) {
      return null;
    }
    String[] parts = cursor.split("\\.", -1);
    if (parts.length != 5 || !cursorVersion.equals(parts[0])) {
      throw new IllegalArgumentException("invalid cursor");
    }
    if (!constantTimeEquals(parts[1], digest(root.getBytes(StandardCharsets.UTF_8)))
        || !constantTimeEquals(parts[2], digest(needle))) {
      throw new IllegalArgumentException("cursor does not belong to this search");
    }
    try {
      String path = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
      long offset = Long.parseLong(parts[4]);
      if (offset < 0 || !path.startsWith("/")) {
        throw new IllegalArgumentException("invalid cursor");
      }
      return new Cursor(path, offset);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid cursor", error);
    }
  }

  private static boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private static String digest(byte[] value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte valueByte : bytes) {
        result.append(String.format("%02x", valueByte & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError("SHA-256 is required", error);
    }
  }

  private static FileStamp stamp(SearchFiles.FileInfo status) {
    return new FileStamp(status.size, status.mtimeMillis);
  }

  private static final class Traversal {
    final List<FileRecord> files = new ArrayList<FileRecord>();
    final List<DirectoryRecord> directories = new ArrayList<DirectoryRecord>();
    final List<AgentFS.SearchSkip> skipped = new ArrayList<AgentFS.SearchSkip>();

    boolean contains(String path) {
      for (FileRecord file : files) {
        if (file.path.equals(path)) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class FileRecord {
    final String path;

    FileRecord(String path) {
      this.path = path;
    }
  }

  private static final class DirectoryRecord {
    final String path;
    final FileStamp stamp;

    DirectoryRecord(String path, FileStamp stamp) {
      this.path = path;
      this.stamp = stamp;
    }
  }

  private static final class FileStamp {
    final long length;
    final long modificationTime;

    FileStamp(long length, long modificationTime) {
      this.length = length;
      this.modificationTime = modificationTime;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof FileStamp)) {
        return false;
      }
      FileStamp stamp = (FileStamp) other;
      return length == stamp.length && modificationTime == stamp.modificationTime;
    }

    @Override
    public int hashCode() {
      return (int) (length ^ (length >>> 32) ^ modificationTime ^ (modificationTime >>> 32));
    }
  }

  private static final class Cursor {
    final String path;
    final long offset;

    Cursor(String path, long offset) {
      this.path = path;
      this.offset = offset;
    }
  }

  private static final class ScanResult {
    final List<AgentFS.SearchMatch> matches;
    final boolean reachedLimit;
    final AgentFS.SearchSkip skip;

    ScanResult(List<AgentFS.SearchMatch> matches, boolean reachedLimit, AgentFS.SearchSkip skip) {
      this.matches = matches;
      this.reachedLimit = reachedLimit;
      this.skip = skip;
    }

    static ScanResult matches(List<AgentFS.SearchMatch> matches, boolean reachedLimit) {
      return new ScanResult(matches, reachedLimit, null);
    }

    static ScanResult skip(String path, String reason) {
      return skip(path, reason, null);
    }

    static ScanResult skip(String path, String reason, Integer errno) {
      return new ScanResult(Collections.<AgentFS.SearchMatch>emptyList(), false,
          new AgentFS.SearchSkip(path, reason, errno));
    }
  }
}
