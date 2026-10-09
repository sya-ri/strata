# Complete CPU executor plans

This contract permits CPU JMH collection on independently controlled executors while preserving the complete original paired corpus.
The [performance testkit](performance-testkit.md#collection-ownership) continues to own collection, raw-result validation, loaded-artifact proof and strict per-shard comparison.
Native, GPU, loaded-game, physical-backend parity, upload counts/bytes, full offscreen-through-GUI time and GUI-only GPU gates remain separate wherever the original runtime change requires them.
A successful CPU plan validates measurement completeness; it does not accept a runtime regression or establish native/FPS improvement.

## Admission and frozen bytes

Admit every required suite and both JMH AverageTime and SampleTime modes for both runtime variants before partitioning.
Run complete untimed parity, work, ownership, lifecycle and failure checks against both actual runtime archives.
Use one reconciled fixture, generated metadata, collector, harness, control/input inventory and original execution configuration for the pair.
Keep runtime source revisions, build logs and source archives with that admission.
The complete compiled matrix comes from `JmhWorkloadInventory`, rather than a new fixture registry or a written row-count forecast.

Pass `-Pstrata.performance.cpuAdmission=<fresh-directory>` to an existing `jmhHistorical`, `jmhComponents` or `jmhRemote` invocation with the original full selectors and settings.
The collector verifies complete generated metadata and optional fixture work checks, captures actual loaded trees and preserves complete fixture sources/resources, the generated registry, collector, harness, runtime archives and external inputs.
It writes `admission.json` and returns without starting a JMH fork.
Quick, smoke and incomplete matrices cannot admit a complete CPU plan.
Admission and measurement are separate invocations; supplying `cpuAdmission` and `cpuContext` together fails.
Other consumer entry points calling the same runner may supply the equivalent JVM system properties.
The three shipped task adapters forward these generic properties without adding fixture-specific switches.
They also pass `strata.performance.cpuProbe`, defaulting to the shipped host observer; that observer is an actual preserved external input in every admission and collection.
If using a relocated frozen probe, set the property to its actual path and keep its bytes identical to admission.

The opt-in [outer driver](../../performance-testkit/tools/cpu_plan.py) uses Python's standard library and the packaged existing JVM comparator.
It neither implements another application timer nor schedules remote workers.
An executor owner supplies explicit commands and transfers; an existing CI job matrix or manual controlled execution invokes those commands.

The freeze request contains:

| Field | Contract |
| --- | --- |
| `corpora` | Unique suite IDs, complete `avgt` and `sample` baseline/candidate admissions, source archives for both variants and explicit collection command token arrays. |
| `executors` | Unique executor IDs, actual host/JDK observations and separately reviewed qualification sources. |
| `shards` | Unique shard IDs, fixed executor bindings and generated method/parameter selectors for every required suite. |
| `interleaving` | Exactly six slots: both variants at repetition indexes 0–2, in the declared unchanged order. |
| `max_attempts` | The complete-attempt retry limit fixed before timing. Adoption is always the first complete success. |
| `comparator` | The Java executable and classpath, beginning with the actual common collector JAR; all classpath archives are frozen. |
| `preparation_receipts` | Observed outer preparation records whose artifact hashes include every actual full admission. |

Each corpus's `admissions` maps mode → variant → actual admission-file path.
Its `source_archives` and `commands` map variant → source path or command tokens.
Each shard's `selectors` maps suite ID → an optional `methods` array and `parameters` object with arrays of actual compiled values.
Unknown or duplicate IDs/values fail.
Every shard contains a nonempty selection for every required suite/mode; their disjoint union must exactly equal each full compiled inventory.
The method/parameter selection is identical between JMH modes and runtime variants.
The inventory ID describes the complete qualified suite/mode rows; the plan UUID and its exact file digest additionally bind all bytes, selectors, executor profiles, source archives, commands and policy.

Collection commands have explicit `{workspace}`, `{parameters}`, `{context}`, `{output}`, `{mode}`, `{repetition}` and `{methods}` substitutions.
They invoke the existing generated selector through `strata.performance.workloads`/`strata.performance.parameters` and pass `strata.performance.cpuContext` to the collector.
Keep `strata.performance.benchmarks` at the originally admitted complete fixture family; the shared entry point resolves its generated methods.
The same schema supports independent portable, remote and historical corpora, with no new build change when another fixture is added.
Use fresh output directories and a launcher that leaves no daemon or descendant process behind.

The driver copies admissions and rechecks actual preserved bytes when freezing and again when processing.
It also freezes the executing `cpu_*.py` driver sources, including native process accounting, and requires the executing and preserved copies to match.
Common fixture/generated/resource, collector, harness and control/input identities must match across every shard and both runtime variants.
Only the registered runtime archive bytes may differ between variants; module and representative inventories must match.
Changing artifacts, inventory, selectors, source archives, executor bindings or host contract requires a new plan and complete renewed admission.

```shell
python -B performance-testkit/tools/cpu_plan.py freeze freeze-request.json fresh-plan
```

## Qualifying executors

Record actual observed machine identity, CPU model, JDK launcher/release bytes, OS, affinity, power policy and exclusive occupancy.
The [host observer](../../performance-testkit/tools/cpu_host.py) captures its OS data sources as well as detached conditions:

```shell
python -B performance-testkit/tools/cpu_host.py --java /controlled/jdk/bin/java
```

Capture that output into the executor's `profile` file while the executor is quiescent and under the reviewed control contract.
The driver refuses collection when another JVM occupies its host or its observed conditions change.
One local OS lease covers the entire paired shard, all required suites/modes and every baseline/candidate repetition.
No timed invocations overlap within that lease.
The actual fork's Java executable digest, unique process/fork identity and loaded-code proof accompany every iteration.
Each actual fork verifies the frozen observer and Python executable bytes, reads OS conditions before its first warm-up and after its final measurement, and requires both observations to match its frozen executor profile.
The raw OS/probe sources accompany the fork records; parent-only observations are insufficient.

An OS lease fences other workers using this driver; it cannot establish that unrelated users, services or provider tenants leave the physical CPU idle.
The qualification source must independently establish physical identity/independence, permission to use the executor, exclusive occupancy, power/affinity control and the duration for which those controls remain valid.
Review the raw provider/OS/host-admin sources, not a label declaring qualification.
A VM's DMI UUID or CI runner label alone does not establish physical independence or power control.
Unknown or unavailable conditions prevent formal acceptance.
The observer intentionally fails when it cannot obtain authoritative power-policy sources.
Do not weaken this requirement to turn an ordinary hosted VM or multiple processes on one shared host into qualifying independent executors.

Use the same fixed shard → same physical executor mapping in serial and parallel scheduling.
Serial scheduling runs those shards sequentially on their assigned executors; parallel scheduling grants those same executors simultaneous permission to run.
Do not place all serial shards on one host and compare them with a faster redistributed parallel fleet.
A changed binding/profile requires a new plan and both complete scheduling modes.
No plan authorizes new paid resources, provisioning, provider changes or production workflow dispatch.

## Whole attempts and explicit permits

An attempt names one plan/inventory, one scheduling mode, one whole-attempt UUID and exactly one distinct shard-attempt UUID for each frozen shard.
Each collector context also names shard, suite, JMH mode, variant, repetition, executor, lease and selector identity.
The collector owns its distinct invocation UUID; each real fork owns a distinct fork UUID and every actual warm-up/measurement iteration owns a distinct iteration UUID.
These identities are additional to the existing per-iteration loaded-artifact confirmation.
The raw receipt remains an ordinary selected receipt and is never relabelled as an existing full-matrix receipt.

The coordinator creates serial scheduling first:

```shell
python -B performance-testkit/tools/cpu_plan.py begin fresh-plan collection serial
python -B performance-testkit/tools/cpu_plan.py grant fresh-plan collection/serial-0 shard-a
```

Transfer the frozen bundle and attempt/permit bytes to the fixed executor.
Run its complete paired shard there, preserving the original workspace/runtime classpaths:

```shell
python -B performance-testkit/tools/cpu_plan.py run-shard fresh-plan collection/serial-0 shard-a /prepared/source
```

Transfer the complete resulting shard bundle back without rewriting receipt, raw, archive, log, profile or context files.
The coordinator acknowledges that exact source:

```shell
python -B performance-testkit/tools/cpu_plan.py ack fresh-plan collection/serial-0 shard-a
```

A serial permit for the next shard requires acknowledgement of every predecessor.
This explicit barrier records coordinator occupancy including queue/transfer/acknowledgement costs, without relying on synchronized clocks between executors.
The coordinator's physical identity and boot identity must remain unchanged so its monotonic durations cannot cross a reboot or another host.
Parallel scheduling grants every shard before collecting acknowledgements on those same bindings.
Granting permission does not prove that the workers actually overlap or that a scheduling gain occurred; the observed whole wall time and actual executor durations determine the reported outcome.
Permits, shard output directories and acknowledgements are created once and cannot be replaced.

After every required shard finishes:

```shell
python -B performance-testkit/tools/cpu_plan.py finish fresh-plan collection/serial-0
```

Whole-attempt validation requires:

- The exact expected shard/attempt set and every mode, suite, variant and repetition, with no extra, duplicate, overlapping, missing or foreign row.
- The frozen exact generated row union and selector bytes; original iteration durations/counts, forks, threads, profiler/GC settings and input controls.
- Matching actual collector/harness/fixture/resource/input/runtime/archive/class-tree identities.
- Globally unique collector/fork/iteration identities, complete actual warm-up/measurement provenance and unchanged executor/JDK bindings/conditions.
- A new invocation and independent forks for each paired slot, retaining all raw timing/allocation distributions.
- Existing strict `JmhPerformanceEvidence.compare` over raw baseline 3 / candidate 3 for every shard/suite/mode.

The dedicated whole-attempt result has the `strata-jmh-cpu-whole-attempt-v1` contract.
Concatenating selected summaries or subset receipts cannot create it.
Its successful status means the whole measurement attempt validates, independently of whether the retained runtime comparison contains regressions.

## Failure and adoption

Failures retain raw results, archives, logs, completed slots, contexts, provenance and a terminal failed whole outcome.
An incomplete attempt cannot be reused or silently replaced; finalize it as failed first.
Retrying creates new whole/shard attempt IDs and recollects **every shard, every required suite/mode and every baseline 3 / candidate 3 slot**.
Rerunning one failed shard, one missing mode or a slower row is insufficient.
Never adopt successful fragments from a failed attempt, replace slower successful runs or merge results from different attempts.
Preserve earlier failed and successful-but-slower attempts; an already successful complete attempt cannot be retried to select a faster one.
Missing earlier attempts, altered failure sources, reused invocation/fork/iteration IDs and incomplete recollection fail collection validation.

After a complete serial attempt, create `parallel-0` with the same plan and follow the same explicit grant/run/ack/finish procedure.
The collection comparison revalidates both raw attempts and every retained failure before adopting the first complete successful attempt per scheduling mode.
It compares every recomputable outcome field against that raw revalidation.
Whole-attempt and processing durations are recalculated from the separate preserved `completion-clock.json` bookends, bound to the actual attempt and the coordinator's physical/boot identity.
Changing a saved outcome's wall, CPU, allocation or other derived fields fails validation.

```shell
python -B performance-testkit/tools/cpu_plan.py begin fresh-plan collection parallel
python -B performance-testkit/tools/cpu_plan.py compare fresh-plan collection fresh-scheduling-comparison.json
```

The scheduling comparison has its own `strata-jmh-cpu-scheduling-comparison-v1` contract.
It preserves every per-shard strict CPU/allocation comparison, including controls and regressions; it does not replace the existing summary/comparison format.

## Measurement sources and reported costs

Measure untimed preparation/admission and explicit transfers with the outer phase driver:

```shell
python -B performance-testkit/tools/cpu_plan.py measure-phase preparation-command.json fresh-preparation-cost
```

A phase request contains `phase` (`preparation` or `transfer`), `workspace`, an explicit `command` token array, `artifacts` naming actual output files, and `binding` naming its intended complete admission or plan/whole-attempt/scheduling/shard.
It preserves the command specification, actual output/log bytes, artifact hashes/byte counts, exit status, elapsed time and process-tree CPU accounting.
Failed phase sources remain failures.
Retained successful or failed transfers from earlier failed whole attempts remain visible as diagnostic and cost sources.
Only complete successful transfers belonging to the adopted successful attempts satisfy their required transfer sets.
Foreign or omitted attempts, changed source/log bytes, and incomplete phase folders fail validation.
Every full admission must appear in the preparation artifacts before freeze.
Every returned complete shard must appear in an observed transfer source under `collection/transfers/<unique-phase>/phase.json` before scheduling comparison.
An unmeasured phase is Pending, rather than a measured zero.

On Windows, the driver assigns a suspended root process to a Job before it can create children and accounts its inherited process tree.
On Linux, an isolated child-subreaper helper owns the command's complete descendants, including children that create another session.
The driver accounts that dedicated helper with `wait4`, including the CPU of its reaped descendants, and rejects a root that leaves live or unreaped children.
Cleanup signals only kernel-verified direct children through PID handles, repeatedly adopting and reaping remaining descendants; it never signals unrelated process IDs or global groups.
Unavailable ownership APIs or incomplete cleanup fail collection; an executor with incomplete cleanup must remain quarantined until its owner verifies release.
Neither substitutes aggregate CPU seconds for actual elapsed wall time.
JMH continues to supply GC and normalized allocation.
The CPU profiler also preserves JMH's actual all-operation counts per iteration; aggregate measured allocation is the sum of matched per-iteration operation counts × normalized allocated bytes, rather than a sum of B/op values across unrelated rows.
This allocation total covers the JMH measured iterations, not preparation, process startup, unmeasured warm-ups or total host memory.
The validation profiler precedes the CLI's GC profiler in JMH's registration order, so its evidence publication follows GC's reverse-order completion hook.
Do not change that ordering: adding evidence allocations inside the GC collection interval contaminates the admitted allocation comparison.

Each fork preserves its OS process-start timestamp and the timestamp after its first untimed host/provenance verification.
Their nonnegative difference reports observed startup plus readiness verification, separate from JMH samples.
Each collector also records monotonic parent-preparation, JMH-harness and parent-processing durations in `cpu-costs.json`.
Missing fork-startup or collector-phase sources reject the complete attempt rather than becoming measured zeros.

Report actual whole wall time, queue/longest coordinator occupancy, longest executor time, aggregate executor time, aggregate CPU time, measured allocation, preparation, transfer, retry and processing costs.
Keep collector invocations separate from actual row forks.
Include each side's three independent values and dispersion, all controls and every regression/rejection.
The collection wall time includes its retained retries and coordination; preparation sources are reported separately so reused and duplicated preparation remain visible.
An arithmetic sampling critical-path forecast is not an observed gain or a reduction in total CPU work.
Do not claim the four-executor forecast when physical four-executor qualification or complete observed scheduling evidence is missing.

## Verification

`:performance-testkit:verifyCpuExecutorPlan` runs deterministic synthetic plan/attempt rejection tests and participates in complete-model `check`.
The synthetic test sources, scores and host profiles are not performance evidence.
Ordinary collector tests, JVM/platform tests, Detekt, Kotlinter, explicit API, warnings-as-errors, ABI validation and JVM-after-test Kover HTML/XML remain mandatory.
Before review, run the final-revision full command from [build verification](build.md#quality-checks) and the required change-scoped CI/Qodana/platform gates.
Do not add a fixed wall-time CI threshold or replace native requirements with CPU proof.
