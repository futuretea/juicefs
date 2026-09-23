# AgentFS search providers

AgentFS keeps one search operation while applications select its implementation
when constructing the SDK. Python and Java use the same request and result
semantics. The canonical contract cases live in
`sdk/agentfs/fixtures/search_cases.json`; every SDK test suite runs that single
source. Filesystem credentials, reads, writes, listing, and client lifetime
remain with each standalone SDK's connection. No provider registry or service is
required.

```python
from agentfs import AgentFS, RawSearchProvider, RipgrepSearchProvider

fs = AgentFS(client)  # RawSearchProvider by default
fs = AgentFS(client, search_provider=RipgrepSearchProvider(executable="rg"))
page = fs.search("needle", root="/project", limit=20)
```

```java
AgentFS fs = AgentFS.builder(volume, metadataUrl)
    .identity(user, java.util.List.of(group)).open(); // RawSearchProvider by default
fs = AgentFS.builder(volume, metadataUrl)
    .identity(user, java.util.List.of(group))
    .searchProvider(new RipgrepSearchProvider("rg")).open();
AgentFS.SearchPage page = fs.search("needle", "/project", 20, null);
```

## Extension contract

Python implements `SearchProvider.search(client, query, root, limit, cursor)`;
Java implements `SearchProvider.search(files, query, root, limit, cursor)` with
the standalone SDK's `SearchFiles` view. Providers receive storage access on
each call. Their configuration
belongs to application setup, not to each Agent tool invocation. A provider can
replace the whole search operation; the streaming scanner is only an internal
helper shared by the two built-in providers, not a restriction on future engines.
The built-ins keep per-search state local and can be reused by concurrent
requests. Custom providers used concurrently must handle their own shared state.

| Contract | Required behavior |
|---|---|
| Query | Nonempty, strictly UTF-8-encodable literal; case-sensitive; no normalization |
| Scope | Absolute directory root, recursively including ordinary hidden files |
| Root | Normalized before the provider runs; a leading `//` is rejected like a relative path |
| Matches | Every overlapping occurrence; absolute path, zero-based byte offset and byte length |
| Order | UTF-8 path bytes, then ascending byte offset |
| Page | At most `limit` matches; opaque continuation or null |
| Cursor | Bound to provider, query, and root; switching provider starts a new search |
| Coverage | `complete` only after traversal ends with no known skips; not a snapshot |
| File errors | `skipped` records known list/read/change gaps; never silently claim absence |
| Skip detail | An existing non-directory root records `non_directory_root`; entries may carry an optional `errno`, null when the storage layer exposes none |
| Engine errors | Python `SearchProviderError`, Java `SearchProviderException`; no fallback |

Both built-ins flag concurrent modification by comparing file size and mtime
before and after each file and directory; ctime-only metadata changes are not
content changes. Python and Java share one error vocabulary:

| Condition | Message |
|---|---|
| Malformed cursor or provider (version) mismatch | `invalid cursor` |
| Cursor from another root or query | `cursor does not belong to this search` |
| Search root not absolute (relative or leading `//`) | `root must be an absolute path` |
| Non-positive limit | `limit must be a positive integer` |
| Ripgrep fails to start | `could not execute ripgrep` |
| Ripgrep exits outside 0/1 | `ripgrep failed (exit N)` |

Both built-ins observe live files. Metadata checks can detect some concurrent
changes but do not create a consistent filesystem snapshot, within or across
pages. A subsequent search reads file contents again; there is no index refresh
delay. An indexed provider will need an explicit coverage/freshness contract
before claiming equivalent searches of current file contents. No indexed
provider is implemented in this change.

Cursor version prefixes are now `v1-raw` and `v1-ripgrep`. Cursors from the earlier
experimental unqualified `v1` format are rejected; restart those searches. No
persistent JuiceFS metadata or file format changes.

## Built-in implementations

`RawSearchProvider` compares byte windows inside the SDK. `RipgrepSearchProvider`
passes the same windows from the same SDK filesystem to an installed `rg`
executable. Neither requires a mount or a local mirror. Traversal, 1 MiB reads,
boundary overlap, ordering, pagination, and file-change checks are shared.

The ripgrep adapter uses PCRE2 lookahead plus a one-byte match to preserve
overlapping literal occurrences. Query bytes become hex escapes; argv is passed
directly without a shell. It disables configuration loading, Unicode matching,
and encoding conversion, includes binary contents, and permits multiline
matches. Output contains numeric offsets and a constant replacement, not file
contents. stderr stays on its own pipe and never changes the outcome: only the
exit code decides success (`0` or `1`) or failure (anything else, reported as
`ripgrep failed (exit N)`; the Java adapters append the captured stderr). Offset lines are validated strictly while read; each must
be `<digits>:X`, strictly increasing, and within the current window, and any
violation fails the search immediately. Details of the underlying options are
in the
[ripgrep guide](https://github.com/BurntSushi/ripgrep/blob/master/GUIDE.md) and
[FAQ](https://github.com/BurntSushi/ripgrep/blob/master/FAQ.md).

`rg` must be available with PCRE2 support (`rg --version`). Its executable path
is trusted application configuration. Missing executables, unsupported PCRE2,
engine pattern-size limits, or process failures raise provider errors; the
adapter does not silently use raw scanning. Very large queries may exceed PCRE2
or operating-system argument limits even when raw scanning can accept them.

Each byte window starts one subprocess. Pipe copies, process startup, matching,
and output parsing are all included in query latency. This deliberately simple
adapter is a comparison baseline, not a claim about native ripgrep's optimal
local-directory performance. Memory grows with the window/query and result
page; Python also captures the window's offset output before returning a page.
Dense overlapping matches can be expensive even with `limit=1`, because rg
finishes the current window and the adapter checks its exit status. This is a
known limitation of this baseline adapter.

## Verification

The independent Python and Java package verifiers consume the shared search
fixtures and exercise both Raw and Ripgrep providers on isolated volumes. Run
`bash sdk/agentfs-python/scripts/verify.sh all`, then run the Java verifier with
`AGENTFS_META` and `AGENTFS_VOLUME` set to an existing isolated test volume, as
shown in the [shared SDK guide](README.md). The old branch-only provider
benchmark was removed with the old SDK AgentFS entry points.
