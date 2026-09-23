# Local JVM cost baseline

This is a synthetic local measurement, not a production benchmark or a release
gate. It does not change the old SDK. Build the new SDK/native artifact and the
independent consumer dependencies before running it.

From the host, build the runner image, provide `AGENTFS_META` and
`AGENTFS_VOLUME` through trusted application configuration, set
`AGENTFS_NETWORK` to an existing authorized test network, then run the cost
script:

```sh
docker build -f sdk/agentfs/Dockerfile.runner -t agentfs-goal-sdk-runner .
AGENTFS_NETWORK="$ISOLATED_NETWORK" AGENTFS_META="$ISOLATED_META" \
  AGENTFS_VOLUME="$ISOLATED_VOLUME" bash sdk/agentfs-java/cost/run.sh
```

The script retains a historical default network name that may not exist on a
fresh host; set `AGENTFS_NETWORK` explicitly. No service is formatted or reset.

Inside that Linux runner, `bash sdk/agentfs-java/cost/container.sh` runs directly
without Docker-in-Docker. Set `AGENTFS_COST_OUTPUT_DIR` for evidence and optionally
`AGENTFS_COST_NEW_JARS` for the independent consumer's runtime dependency
directory. Metadata and volume variables are inherited. Every invocation creates
one unique synthetic file and leaves it in place; there is no cleanup of existing
volume data.

## Method

Both consumers read and verify the same 4096 ASCII `a` bytes: the standalone SDK
uses `AgentFS.readFile`, and the old Hadoop SDK uses its existing `FileSystem.open`.
They use JDK 17, `-Xms32m -Xmx512m`, the same native
library, trusted `hdfs`/`supergroup` identity, Java native caller mode, memory cache
100 MiB, I/O buffer 300 MiB and disabled usage reporting. The old SDK explicitly
sets the cache/config values; the new SDK's effective defaults are those values.
Other effective native defaults retain the old Java defaults.

Five rounds alternate old/new JVMs within one container. Timing starts in the
parent immediately before process creation and ends when the verified-read
marker arrives. Each JVM then waits on stdin while `ps` records one positive RSS
sample. The measurement includes in-process Go/JNR memory but excludes services;
it is not peak RSS. The process must close successfully. Missing/wrong markers
and missing/invalid/nonpositive RSS fail rather than becoming zero samples.

The old runtime is its normal shaded SDK plus Hadoop common 3.1.4 and transitive
dependencies; the new runtime is the independent consumer's full dependency
directory. JAR counts include the SDK itself. Size reporting counts actual
runtime artifacts and streams the bundled libjfs gzip to count uncompressed
bytes. Raw samples and min/median/max are written to `cost.json`.

## Current observation (2026-09-23 UTC)

The final `verify.sh all` route passed on Linux aarch64/JDK 17 after the old
AgentFS wrapper was removed. Its old-side measurement uses `FileSystem.open`;
the independent consumer uses `AgentFS.readFile`. The verifier retained raw
samples in its `target/verify-*/cost/cost.json` output.

| Metric | Old Hadoop SDK | Standalone SDK |
|---|---:|---:|
| Runtime JARs | 93 | 12 |
| Runtime bytes | 116831099 | 72135668 |
| SDK JAR bytes | 82991326 | 69630084 |
| First verified read ms, min / median / max | 211.889 / 235.284 / 886.822 | 619.486 / 636.806 / 687.578 |
| Held-process RSS KiB, min / median / max | 174092 / 174472 / 176220 | 154052 / 156296 / 159564 |

The standalone runtime used fewer JARs and lower sampled RSS, but its median
first verified read was slower. Five local samples do not establish a general
latency or memory claim.

## Historical observation (2026-09-18; superseded)

This run predates removal of the branch-added AgentFS wrapper from the old
Hadoop SDK. Its old-side read path is not the `FileSystem.open` path in the
current method above, so these numbers are retained only as historical evidence
and must not be used as the current old/new comparison.

Executed `bash sdk/agentfs-java/cost/run.sh`, exit 0, evidence in
`results-6XZVH93Y/`. Platform: Linux aarch64, OpenJDK 17.0.20.1; baseline commit
`4aee2c9efae7548cacdebacce34e364be299234a` plus the uncommitted standalone SDK.

| Metric | Old Hadoop SDK | Standalone SDK |
|---|---:|---:|
| Runtime JARs | 93 | 12 |
| Runtime bytes | 116876486 | 72128862 |
| SDK JAR bytes | 83036713 | 69623278 |
| Bundled native gzip bytes | 70071413 | 70071413 |
| Expanded libjfs bytes | 177786448 | 177786448 |
| First verified read ms, min / median / max | 258.497 / 269.983 / 969.740 | 676.155 / 698.156 / 789.527 |
| Held-process RSS KiB, min / median / max | 176840 / 179152 / 179616 | 156064 / 159256 / 159572 |

Raw `(elapsed milliseconds, RSS KiB)` samples, in alternating round order:

| Round | Old | New |
|---|---|---|
| 1 | 969.740126, 179616 | 789.527459, 156064 |
| 2 | 301.908875, 179152 | 736.528208, 159264 |
| 3 | 259.636792, 177368 | 698.155750, 156100 |
| 4 | 258.496667, 176840 | 676.154667, 159572 |
| 5 | 269.982750, 179168 | 676.366375, 159256 |

This run shows fewer dependencies and a lower observed RSS, **not faster median
startup**. A material loader difference is present: the old
`JuiceFileSystemImpl.loadLibrary` reuses a timestamped library in `/tmp`, while
new `NativeLibrary.load` extracts into a new temporary file for each JVM. The
first old sample includes extraction; subsequent samples can reuse it. Source
inspection proves that difference, but no profiler isolated its exact share of
the latency. No loader optimization was added for this measurement.

Host/storage cache state is uncontrolled, and five samples are not a stable
throughput or universal performance claim. The native library dominates the
standalone artifact; removing Hadoop does not make the SDK pure Java. Re-running
the command against the same effective configuration is the reproduction check.

The first preparation attempt, `results-gLBlgDPs`, stopped before measurement
because the independently tested measurement module had not yet been created.
It produced no performance samples and is not counted above.

## Checks

`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s
sdk/agentfs-java/cost -p test_measure.py -v`: four independent Red-authored parser
tests pass. `shellcheck sdk/agentfs-java/cost/*.sh` and `git diff --check` pass.
The later path/env wiring generalization retains the measured algorithm; its
integrated runner execution is recorded by the main verification workflow.
