# Standalone Python AgentFS

This package installs as `agentfs` and contains its own JuiceFS native library. Applications do not install or import the older `juicefs` Python distribution.

```python
from agentfs import AgentFS, Client, RipgrepSearchProvider

client = Client("my-volume", "sqlite3:///path/to/meta.db")
try:
    fs = AgentFS(client)
    data = fs.read_file("/notes.txt", offset=0, limit=4096)["data"]
    fs.write_file("/notes.txt", data + b"\n")
    page = fs.list_directory("/", limit=20)
    matches = AgentFS(client, search_provider=RipgrepSearchProvider()).search("notes")
finally:
    client.close()
```

The caller owns `Client` and its credentials. Default `close()` flushes and closes this handle while retaining the native filesystem cache for reuse; `close(terminate=True)` also closes the native filesystem when this is its last active client handle. `AgentFS` does not change the native client's permissions and is not a sandbox. Paths and file bytes supplied by an agent must remain within the application's own authorization policy.

`read_file`, `write_file`, `list_directory`, `search`, built-in search providers, and direct `Client` path methods reject paths containing NUL (`\0`) with `ValueError` before native I/O. Editing also rejects NUL paths, using its existing `EditError` with code `invalid_input`. The older `juicefs` Python SDK is unchanged.

Search uses UTF-8 byte offsets and returns `complete` and `skipped` so callers can detect incomplete scans. Raw search is the default; `RipgrepSearchProvider` must be selected explicitly and requires `rg`. Missing `rg` raises `SearchProviderError` rather than silently using Raw. Exact replacement and single-file patch errors use `agentfs.edit.EditError`; an `unknown` outcome means the commit may have happened and must not be retried blindly. Python and Java apply the same EOF rules and run the same shared edit fixtures: deleting only `dog` from `cat\ndog` leaves `cat\n`.

The verified target in this branch is CPython 3.11 on Linux/arm64. Other Python versions or platforms are not claimed. Build and test with `bash sdk/agentfs-python/scripts/verify.sh all` from a Docker-capable host. The script creates a fresh SQLite+file volume in a temporary directory, builds a platform wheel, audits its embedded AArch64 ELF library and metadata, then installs only the wheel into a separate source-free consumer container. It does not format or write an existing volume. `bash sdk/agentfs-python/scripts/verify.sh build` is the inner Linux/arm64 wheel-build route.
