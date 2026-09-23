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
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/** A client owned by the application. Finish operations before closing it. */
public final class AgentFS implements AutoCloseable {
    private final NativeClient client;
    private final SearchProvider searchProvider;

    AgentFS(NativeClient client) {
        this(client, new RawSearchProvider());
    }

    AgentFS(NativeClient client, SearchProvider searchProvider) {
        this.client = client;
        this.searchProvider = searchProvider;
    }

    public static Builder builder(String volume, String metadataUrl) {
        return new Builder(volume, metadataUrl);
    }

    public ReadResult readFile(String path, long offset, int limit) throws IOException {
        return client.readFile(path, offset, limit);
    }

    public WriteResult writeFile(String path, byte[] data) throws IOException {
        return client.writeFile(path, data);
    }

    /** Exact unique UTF-8 replacement. Callers must serialize editing of each file. */
    public EditResult editFile(String path, String oldText, String newText) throws IOException {
        return new AgentFileEdit(client.editStorage()).editFile(path, oldText, newText);
    }

    /** Single-file context patch. Never automatically retry a commit_unknown error. */
    public EditResult applyPatch(String path, String patch) throws IOException {
        return new AgentFileEdit(client.editStorage()).applyPatch(path, patch);
    }

    /** Exact search with explicit coverage gaps; results are not a filesystem snapshot. */
    public SearchPage search(String query, String root, int limit, String cursor) throws IOException {
        client.requireOpen();
        if (root == null || !root.startsWith("/") || root.startsWith("//")) {
            // Shared search vocabulary, aligned with the Python and Hadoop facades.
            throw new IllegalArgumentException("root must be an absolute path");
        }
        return searchProvider.search(client, query, Inputs.path(root), limit, cursor);
    }

    /** Lists a UTF-8 path-ordered page. Pagination does not provide a snapshot. */
    public DirectoryPage listDirectory(String path, long offset, int limit) throws IOException {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("Invalid directory offset or limit");
        }
        List<SearchFiles.FileInfo> files = client.list(path);
        files.sort((a, b) -> Arrays.compareUnsigned(a.path.getBytes(StandardCharsets.UTF_8),
                b.path.getBytes(StandardCharsets.UTF_8)));
        int start = (int) Math.min(offset, files.size());
        int end = (int) Math.min((long) files.size(), (long) start + limit);
        List<DirectoryEntry> entries = new ArrayList<>();
        for (int index = start; index < end; index++) {
            SearchFiles.FileInfo file = files.get(index);
            entries.add(new DirectoryEntry(file.path.substring(file.path.lastIndexOf('/') + 1),
                    file.size, file.type));
        }
        return new DirectoryPage(entries, end < files.size(), end);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    public static final class Builder {
        private final String volume;
        private final String metadataUrl;
        private String user;
        private List<String> groups;
        private String superuser = "hdfs";
        private String supergroup = "supergroup";
        private SearchProvider searchProvider = new RawSearchProvider();

        private Builder(String volume, String metadataUrl) {
            this.volume = volume;
            this.metadataUrl = metadataUrl;
        }

        public Builder identity(String user, List<String> groups) {
            this.user = user;
            this.groups = groups == null ? null : new ArrayList<>(groups);
            return this;
        }

        public Builder superuser(String name, String group) {
            this.superuser = name;
            this.supergroup = group;
            return this;
        }

        public Builder searchProvider(SearchProvider provider) {
            if (provider == null) {
                throw new IllegalArgumentException("searchProvider must not be null");
            }
            this.searchProvider = provider;
            return this;
        }

        public AgentFS open() throws IOException {
            Inputs.text(volume, "volume");
            Inputs.text(metadataUrl, "metadataUrl");
            Inputs.text(user, "user");
            Inputs.text(superuser, "superuser");
            Inputs.text(supergroup, "supergroup");
            if (groups == null) {
                throw new IllegalArgumentException("groups must be supplied");
            }
            for (String group : groups) {
                Inputs.text(group, "group");
                if (group.indexOf(',') >= 0) {
                    throw new IllegalArgumentException("group must not contain a comma");
                }
            }
            return new AgentFS(new NativeClient(volume, metadataUrl, user,
                    String.join(",", groups), superuser, supergroup), searchProvider);
        }
    }

    public static final class ReadResult {
        public final String path;
        public final byte[] data;
        public final long offset;
        public final long nextOffset;
        public final boolean truncated;

        ReadResult(String path, byte[] data, long offset, boolean truncated) {
            this.path = path;
            this.data = data;
            this.offset = offset;
            this.nextOffset = offset + data.length;
            this.truncated = truncated;
        }
    }

    public static final class DirectoryEntry {
        public final String name;
        public final long size;
        public final String type;

        DirectoryEntry(String name, long size, String type) {
            this.name = name;
            this.size = size;
            this.type = type;
        }
    }

    public static final class DirectoryPage {
        public final List<DirectoryEntry> entries;
        public final boolean truncated;
        public final long nextOffset;

        DirectoryPage(List<DirectoryEntry> entries, boolean truncated, long nextOffset) {
            this.entries = List.copyOf(entries);
            this.truncated = truncated;
            this.nextOffset = nextOffset;
        }
    }

    public static final class WriteResult {
        public final String path;
        public final long bytesWritten;

        WriteResult(String path, long bytesWritten) {
            this.path = path;
            this.bytesWritten = bytesWritten;
        }
    }

    public static final class EditResult {
        public final String path;
        public final int replacements;
        /** Full output length, or zero when no rewrite was needed. */
        public final long bytesWritten;

        EditResult(String path, int replacements, long bytesWritten) {
            this.path = path;
            this.replacements = replacements;
            this.bytesWritten = bytesWritten;
        }
    }

    public static final class SearchMatch {
        public final String path;
        public final long offset;
        public final int length;

        public SearchMatch(String path, long offset, int length) {
            this.path = path;
            this.offset = offset;
            this.length = length;
        }
    }

    public static final class SearchSkip {
        public final String path;
        public final String reason;
        /** OS-level error number when the storage layer exposes one, otherwise null. */
        public final Integer errno;

        public SearchSkip(String path, String reason) {
            this(path, reason, null);
        }

        public SearchSkip(String path, String reason, Integer errno) {
            this.path = path;
            this.reason = reason;
            this.errno = errno;
        }
    }

    public static final class SearchPage {
        public final List<SearchMatch> matches;
        public final String nextCursor;
        public final boolean complete;
        public final List<SearchSkip> skipped;

        public SearchPage(List<SearchMatch> matches, String nextCursor, boolean complete, List<SearchSkip> skipped) {
            this.matches = List.copyOf(matches);
            this.nextCursor = nextCursor;
            this.complete = complete;
            this.skipped = List.copyOf(skipped);
        }
    }
}
