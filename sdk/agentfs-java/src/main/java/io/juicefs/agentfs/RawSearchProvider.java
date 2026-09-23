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
import java.util.ArrayList;
import java.util.List;

/** Exact byte search performed directly in the SDK. */
public final class RawSearchProvider implements SearchProvider {
  @Override
  public AgentFS.SearchPage search(SearchFiles fileSystem, String query, String root, int limit, String cursor)
      throws IOException {
    return new ExactSearch(fileSystem, "raw", new ExactSearch.Matcher() {
      @Override
      public List<Integer> find(byte[] window, byte[] needle, long minimum, int count) {
        List<Integer> offsets = new ArrayList<Integer>();
        if (minimum > window.length) {
          return offsets;
        }
        for (int found = ExactSearch.indexOf(window, needle, (int) Math.max(0, minimum));
            found >= 0; found = ExactSearch.indexOf(window, needle, found + 1)) {
          offsets.add(found);
          if (offsets.size() == count) {
            break;
          }
        }
        return offsets;
      }
    }).search(query, root, limit, cursor);
  }
}
