# Standalone AgentFS SDKs

Python installs as `agentfs`; Java installs as `io.juicefs:agentfs-java`.
Applications do not need the older Python `juicefs` or Hadoop SDK to use
AgentFS. See the [Python](../agentfs-python/README.md) and
[Java](../agentfs-java/README.md) package guides for installation and platform
limits. This directory holds the shared search/edit contract fixtures.

From the repository root, the Python verifier builds a wheel and tests it on
a newly created SQLite+file volume. The Java verifier requires an existing
isolated test volume supplied by the caller:

```sh
bash sdk/agentfs-python/scripts/verify.sh all
AGENTFS_META="$ISOLATED_META" AGENTFS_VOLUME="$ISOLATED_VOLUME" \
  bash sdk/agentfs-java/scripts/verify.sh all
```

## Public AgentFS API

The standalone SDKs own their connections to an existing JuiceFS volume.
They do not add a service, index, mount, or authorization boundary. Both expose
bounded byte reads, replacement writes, deterministic directory pages, and a
case-sensitive UTF-8 literal search with byte offsets.
All path arguments are absolute.

```python
from agentfs import AgentFS, Client

client = Client("myjfs", "redis://localhost")
try:
    fs = AgentFS(client)  # Default: RawSearchProvider
    page = fs.search("needle", root="/project", limit=20)
    for match in page["matches"]:
        print(fs.read_file(match["path"], offset=match["offset"], limit=4096))
finally:
    client.close()
```

```java
try (AgentFS agentFS = AgentFS.builder(volume, metadataUrl)
        .identity(user, java.util.List.of(group)).open()) {
  AgentFS.SearchPage page = agentFS.search("needle", "/project", 20, null);
  for (AgentFS.SearchMatch match : page.matches) {
    AgentFS.ReadResult context = agentFS.readFile(match.path, match.offset, 4096);
  }
}
```

Continue search with the opaque `next_cursor` / `nextCursor` until it is null.
`complete=true` only says that the observed traversal has no known list, read,
or change skip; it is not a cross-call snapshot. Inspect `skipped` before
treating an empty page as no match. Query matching is raw bytes after UTF-8
encoding, so no tokenization, normalization, preview, or semantic expansion is
performed. Use `read_file` / `readFile` to retrieve context.

Search implementations are selected at construction: Python
`AgentFS(client, search_provider=RipgrepSearchProvider())`, Java
`AgentFS.builder(volume, metadataUrl).identity(user, groups).searchProvider(new RipgrepSearchProvider("rg")).open()`. The operation and result
shape stay the same. See [search providers](SEARCH_PROVIDERS.md) for the extension
contract, cursor scope, and ripgrep requirements.

Manticore is intentionally absent. Its token-based full-text semantics cannot
establish the required no-false-negative guarantee for arbitrary literal file
search, so it is not a valid AgentFS exact-search backend.

## Single-file editing

For SDK developers using the independent Python `Client` or Java `AgentFS`;
this section describes the local-development editing contract.

```python
from agentfs import AgentFS
from agentfs.edit import EditError

fs = AgentFS(client)
result = fs.edit_file("/notes.txt", "hello cat", "hello dog")
result = fs.apply_patch("/notes.txt", """*** Begin Patch
*** Update File: /notes.txt
@@
-hello dog
+hello fox
*** End Patch
""")
```

```java
AgentFS agent = AgentFS.builder(volume, metadataUrl)
    .identity(user, java.util.List.of(group)).open();
AgentFS.EditResult result = agent.editFile("/notes.txt", "hello cat", "hello dog");
result = agent.applyPatch("/notes.txt",
    "*** Begin Patch\n*** Update File: /notes.txt\n@@\n-hello dog\n+hello fox\n*** End Patch\n");
```

Paths are absolute. Text parameters must encode as strict UTF-8. `old_text`
must be nonempty and match exactly once, including overlapping occurrences;
empty replacement text deletes the match. Unmodified bytes need not be UTF-8
and are copied unchanged. Results contain `path`, `replacements`, and
`bytes_written` (`bytesWritten` in Java). The byte count is the complete output
length. Identical output returns zero changes and zero bytes without replacing
the file object; input and matching checks still run.

The patch format accepts exactly one `*** Update File: <path>` between
`*** Begin Patch` and `*** End Patch`. The normalized patch path must equal the
API path. Each `@@` or `@@ <exact anchor line>` block contains space-prefixed
context, `-` deletions, and `+` additions, with at least one change. Anchors
match whole lines uniquely. Blocks match unique, ordered, nonoverlapping
regions of the original file, after their anchor and the preceding block.
All blocks must match before any content is committed. Merge overlapping
context into one block. Line-number hunks, Add/Delete/Move actions, multiple
files, whitespace guessing, and external patch commands are unsupported.

Data-line LF/CRLF bytes are significant. A final `*** End of File` marker
anchors its block at EOF. If the original has no final LF, one final LF or CRLF
is removed from each of the block's old and new sequences, with one exception:
when the block's last output line is a context line that is not the last line
of the old sequence (deletions follow it), the new sequence keeps that context
line's terminator. Untouched bytes take
priority: deleting only `dog` from `cat\ndog` leaves `cat\n`; the preceding LF
is outside the changed region. An empty file accepts one EOF block containing only additions and
remains without a trailing newline. Insertions into nonempty files need old
context. Non-EOF blocks include their data-line terminators.

Only existing regular files with one hard link and no extra ACL/xattr are
supported. Final-component symlinks are rejected; parent-path resolution
follows the existing SDK. Successful edits preserve numeric uid/gid and mode
permission bits, using a checked same-directory temporary file and overwrite
rename. Target read/write and directory create/replace permissions are needed.
Inode, timestamps, open-handle views and sparse layout are not preserved.
The standalone Java editor does not require Hadoop; existing generic Hadoop
operations are unchanged.

Python `EditError` and Java `EditException` carry `code`, `outcome`, and `stage`.
Codes are `invalid_input`, `invalid_patch`, `no_match`, `ambiguous_match`,
`unsupported`, `storage_error`, and `commit_unknown`. `outcome=unchanged`
means this call did not commit; another writer may still have changed the file.
`commit_unknown` means the replacement may already be visible. Read the file
and reconcile the result before another edit; do not blindly retry. A failed
temporary-file cleanup retains its path and cleanup error for diagnosis.
Unknown commits are not retried or automatically undone.

Queue the entire read, generation, and edit sequence for each file, including
other writers. The SDK supplies neither a lock nor a multi-file transaction.
Process crashes can leave temporary files; there is no background cleanup.

Run the independent package verifiers shown above for shared editing fixtures
and isolated-volume behavior. The old branch-only benchmark and integration
scripts were removed with the old SDK AgentFS entry points; performance
measurement is outside this SDK separation goal.
