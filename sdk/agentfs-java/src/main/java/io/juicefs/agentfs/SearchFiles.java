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
import java.util.List;

/** Read-only file access supplied to a search provider. Close every returned stream. */
public interface SearchFiles {
    FileInfo stat(String path) throws IOException;
    List<FileInfo> list(String path) throws IOException;
    InputStream open(String path, long offset) throws IOException;

    final class FileInfo {
        public final String path;
        public final String type;
        public final long size;
        public final long mtimeMillis;

        public FileInfo(String path, String type, long size, long mtimeMillis) {
            this.path = path;
            this.type = type;
            this.size = size;
            this.mtimeMillis = mtimeMillis;
        }
    }
}
