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


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exact byte search using an explicitly selected ripgrep executable with PCRE2 support. */
public final class RipgrepSearchProvider implements SearchProvider {
  private final String executable;

  public RipgrepSearchProvider() {
    this("rg");
  }

  public RipgrepSearchProvider(String executable) {
    if (executable == null || executable.isEmpty()) {
      throw new IllegalArgumentException("executable must not be empty");
    }
    this.executable = executable;
  }

  @Override
  public AgentFS.SearchPage search(SearchFiles fileSystem, String query, String root, int limit, String cursor)
      throws IOException {
    return new ExactSearch(fileSystem, "ripgrep", new ExactSearch.Matcher() {
      @Override
      public List<Integer> find(byte[] window, byte[] needle, long minimum, int count) {
        return run(window, needle, minimum, count);
      }
    }).search(query, root, limit, cursor);
  }

  private List<Integer> run(final byte[] window, byte[] needle, long minimum, int limit) {
    final Process process;
    try {
      process = new ProcessBuilder(executable, "--no-config", "--text", "--multiline",
          "--pcre2", "--no-unicode", "--encoding", "none", "--only-matching",
          "--byte-offset", "--no-line-number", "--no-heading", "--color", "never",
          "--replace", "X", "-e", pattern(needle), "-").start();
    } catch (IOException error) {
      throw new SearchProviderException("could not execute ripgrep", error);
    }
    final AtomicReference<IOException> writeError = new AtomicReference<IOException>();
    Thread writer = new Thread(new Runnable() {
      @Override
      public void run() {
        try (OutputStream input = process.getOutputStream()) {
          input.write(window);
        } catch (IOException error) {
          writeError.set(error);
        }
      }
    }, "agentfs-ripgrep-input");
    final StringBuilder stderr = new StringBuilder();
    Thread errorReader = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader errors = new BufferedReader(
              new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
          char[] chunk = new char[4096];
          int count;
          while ((count = errors.read(chunk)) >= 0) {
            stderr.append(chunk, 0, count);
          }
        } catch (IOException ignored) {
          // stderr is diagnostic only; the exit code alone decides success.
        }
      }
    }, "agentfs-ripgrep-error");
    writer.start();
    errorReader.start();
    try {
      List<Integer> offsets = readOffsets(process, minimum, limit, window.length, needle.length);
      int status = process.waitFor();
      writer.join();
      errorReader.join();
      if (status != 0 && status != 1) {
        String detail = stderr.toString().trim();
        throw new SearchProviderException(
            "ripgrep failed (exit " + status + ")" + (detail.isEmpty() ? "" : ": " + detail));
      }
      if (writeError.get() != null) {
        throw new SearchProviderException("could not stream bytes to ripgrep", writeError.get());
      }
      return offsets;
    } catch (IOException error) {
      throw new SearchProviderException("could not read ripgrep output", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new SearchProviderException("ripgrep execution interrupted", error);
    } finally {
      process.destroyForcibly();
      boolean interrupted = Thread.interrupted();
      while (true) {
        try {
          process.waitFor();
          writer.join();
          errorReader.join();
          break;
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static List<Integer> readOffsets(Process process, long minimum, int limit,
      int windowLength, int needleLength) throws IOException {
    List<Integer> offsets = new ArrayList<Integer>();
    try (BufferedReader output = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
      String line;
      long previous = -1;
      while ((line = output.readLine()) != null) {
        int separator = line.indexOf(':');
        if (separator < 1 || !line.substring(separator).equals(":X")
            || !isAsciiDigits(line, separator)) {
          throw new SearchProviderException("invalid ripgrep offset output");
        }
        long offset;
        try {
          offset = Long.parseLong(line.substring(0, separator));
        } catch (NumberFormatException overflow) {
          throw new SearchProviderException("invalid ripgrep match offset");
        }
        if (offset <= previous || offset + needleLength > windowLength) {
          throw new SearchProviderException("invalid ripgrep match offset");
        }
        previous = offset;
        if (offset >= minimum && offsets.size() < limit) {
          offsets.add((int) offset);
        }
      }
    }
    return offsets;
  }

  private static boolean isAsciiDigits(String line, int end) {
    for (int index = 0; index < end; index++) {
      char value = line.charAt(index);
      if (value < '0' || value > '9') {
        return false;
      }
    }
    return true;
  }

  private static String pattern(byte[] needle) {
    StringBuilder pattern = new StringBuilder("(?=");
    final char[] hex = "0123456789abcdef".toCharArray();
    for (byte value : needle) {
      pattern.append("\\x").append(hex[(value & 0xff) >>> 4]).append(hex[value & 15]);
    }
    return pattern.append(")[\\s\\S]").toString();
  }
}
