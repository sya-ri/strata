# Performance testkit

The `quality:performance-testkit` module owns performance collection and evidence contracts shared by Strata and downstream applications.
It is a test-only Maven publication in the Strata repository, with the same release version as the other modules.
Its JVM coordinate is `dev.s7a.strata:strata-performance-testkit`; Kotlin Multiplatform consumers use `strata-performance-testkit-multiplatform`.
The development branch can be published to an isolated local repository; the first official publication belongs to the next release, without replacing any existing release artifact.

Consumers supply their real components, stable input, actions, readiness predicates, and expected work.
The kit owns clocks, sample collection, distributions, native presentation counters, runtime monitoring, and collector provenance.
Application code must not add another timing engine or interpret unavailable collection as zero work.
JMH remains the harness for JVM microbenchmarks; `JvmPerformanceRunner` preserves existing synchronous workload boundaries when migrating downstream suites.
`JmhPerformanceRunner` delegates unchanged CLI options to JMH and adds an immutable success receipt bound to the actual loaded collector, harness, benchmark fixture and target archives.
It contains no timing or sampling loop and uses the consumer's existing JMH dependency.
`JmhWorkloadInventory` expands the actual JMH-generated benchmark registry into the complete expected parameter/mode matrix; consumers do not implement another annotation-discovery engine.
Unknown parameter names or values, missing generated fixtures and oversized matrices fail before collection.
Each repetition owns a new output directory; failed raw output remains diagnostic evidence and cannot produce a success receipt.
The exact registered benchmark/mode/parameter matrix must complete, and each successful invocation preserves its actual collector, JMH harness and target JARs alongside the raw results.
The adapter requires the standard application/context classloader used by JMH forks; custom fixture loaders fail before execution rather than certifying targets from another loader.
Consumers register external fixture files through `inputs`; the kit bounds, hashes and preserves those files outside measurement, and rejects changed or missing inputs.
Compiled fixture trees identify generated inputs; resource fonts and other external data need explicit file registration.
`JvmPerformanceInputs.read` accepts a standard UTF-8 JDK properties manifest with unique labels and absolute file paths.
The component task registers all resolved non-Strata JVM libraries and native-classifier archives through that manifest, so a dependency change cannot masquerade as a runtime-only comparison.
This preserves the supplied archives; it does not claim a hash of a GPU driver or an independently supplied native library.
The packaged `python -m strata_performance jmh` command validates those archives, raw-result digests, independent repetitions, controlled conditions and complete workload matrices before aggregating with standard-library medians.
Set `PYTHONPATH` to that measured JAR and supply `--collector-jar`, `--output`, and the three invocation directories; the summary refuses to overwrite an existing file.
Use `python -m strata_performance compare-jmh --baseline <three-directories> --candidate <three-directories> --collector-jar <measured-jar> --output <new-file>` for a runtime comparison.
It revalidates both raw suites, requires independent invocations with identical collector, harness, fixture, external inputs, workload matrix and conditions, and permits only target-byte changes within the same module/representative inventory.
It reports medians, deltas and ratios without an absolute timing gate; unavailable GC time and ratios against a zero baseline remain unavailable.

## Evidence and work assertions

`PerformanceCoverage` checks feature/host registration, selects the affected feature union, and rejects missing host/phase execution.
`PerformanceInventory` connects exact discovered module/member identities to those registrations and source ownership.
New, removed, or unassigned API symbols fail before selecting a narrow workload; unknown changed paths fall back to the full suite.
`JvmApiInventory` enumerates the actual loaded archives without class initialization, preserving method overload descriptors and checking every class origin.
It inventories JVM-visible public/protected declarations, including Kotlin declarations exposed publicly in bytecode; it does not infer source visibility or prove that every member has executed.
`verifyEvidence` requires complete independent repetitions with identical inputs, controlled conditions, collector identity, target identity within a suite, and available metrics.
`PerformanceEvidence.requireComparable` allows the measured runtime to change between baseline and candidate, but refuses a collector or workload change.
`WorkExpectation` expresses exact, minimum, and maximum work or retention counts; missing and overflowed diagnostics fail the assertion.
Absolute timing values are diagnostic evidence, not CI thresholds.

`PerformanceDeadline` owns monotonic whole-suite and child-section budgets.
It preserves relative ordering across nano-time wrap and never extends an earlier or expired enclosing deadline.
Whole-suite elapsed time includes preparation and cleanup and must not be reported as an operation's timing distribution.

`PerformanceJson.write` records the actual loaded collector code-source digest separately from the measured application's provenance.
Recollect both baseline and candidate whenever that collector changes.
Historical evidence keeps its original collector and workload contract; field-name compatibility alone is not measurement compatibility.

The JVM JAR also contains the `strata_performance` Python package for existing evidence tools.
It uses standard-library hashing, archive inspection, and median aggregation; it does not implement another benchmark runner.
Import the package directly from the measured testkit JAR and pass that archive to `verify_collectors` before comparing reports.
Every input report must identify that exact archive, including comparisons between different Strata runtime versions.
The shared engine rejects missing collector receipts, duplicate repetitions and phases, missing or non-finite metrics, unsafe class archives, and CPU/native class-tree disagreement.
Its identity is bound when imported; replacing the archive later cannot certify another collector's receipts.
Consumers retain only their workload inventory, fixed correctness expectations, and report presentation.
Frozen historical comparison tools retain their original reviewed-input contract and cannot admit these new collector receipts by field-name substitution.

## Host boundaries

| Entry point | Collection boundary |
| --- | --- |
| `JvmPerformanceRunner` | Caller-thread synchronous operations; fixture preparation and assertions can run outside each sample. |
| `MinecraftPerformanceMeter` | Actual Fabric before/after extraction or rendering callbacks on the measured screen. Frame intervals and render-thread CPU are separate from extraction. |
| `BrowserPerformanceMeter` | Synchronous action wall time and a separate action-to-animation-frame interval, with untimed preparation/assertion hooks. CPU accounting, allocation, and GPU completion remain unavailable. |

The Fabric adapter uses the loaded host's callbacks and counters without depending on a particular runtime artifact in its POM.
Missing callbacks or fields are adapter failures, rather than unsupported zero-cost work.
`NativePerformanceFixture` supplies readiness, expensive evidence capture after warm-up, untimed settling frames, and application snapshots around collection.
Resources and PNG files must be prepared or saved outside steady-state samples.
Closing a meter clears application callbacks, screen references, monitor ownership, and bounded sample storage.

Window readiness uses the loaded host's `isIconified` contract when present, including SDL hosts.
Only the verified legacy `getWindow` family uses GLFW attributes; an opaque modern handle is never passed to another window backend.
Production GameTests put the unchanged testkit JAR on Loom's Java classpath through `LibraryClientProductionRunTask`, rather than publishing it as a Mod or merging its classes into an integration artifact.

The host enum also identifies Paper and Velocity evidence, but an enum value does not prove that a real transport scenario has been registered or run.
Loaded Minecraft family verification, browser executions, and remote server/client measurements must be reported separately from JVM unit tests.

## Local verification

Use the project's mise-selected Java and wrapper.
Update API snapshots separately from the check that validates them; never make ABI checking depend on automatic snapshot updates.

```powershell
mise.exe exec -- ./gradlew.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" "-Pstrata.webOnly=true" :quality:performance-testkit:formatKotlin :quality:performance-testkit:updateKotlinAbi
mise.exe exec -- ./gradlew.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" "-Pstrata.webOnly=true" "-Dmaven.repo.local=<isolated-repository>" :quality:performance-testkit:check :quality:performance-testkit:publishToMavenLocal
```

Inspect the published POM and Gradle module metadata to ensure the kit does not pull another Strata runtime onto the measurement classpath.
Local checks of the kit do not substitute for the full Strata release checks or the application's loaded-client delivery gates.

Runtime diagnostics are captured completely after each successful sample, outside timing, before checkpointing retired node records.
The kit adds global counts with overflow checks and retains final and maximum subscription gauges, without retaining node or frame history.
A single overflowing sample fails the whole interval; a truncated inventory is never accepted as complete evidence.

The Web integration fixture runs `:integration:web:measureWebPerformance` through the kit's Playwright driver in Chromium, Firefox, and WebKit, for three independent repetitions and both themes.
The driver records the actual linked testkit JS artifact and driver hashes separately from the complete application-bundle hash, browser version, viewport, and workload conditions.
Before measurements, each browser verifies that operation and cleanup failures reject the interval and release the fixture exactly once.
Preparation, assertion, collector cleanup, and evidence writing are outside synchronous action timing.

The Minecraft resource-profile cache probe also uses the shared JVM runner, preserving its two explicit fresh extractions, eight warm opens, first open, and post-reload open.
Its pixel, reload, native-hook, immutable-ownership, and weak-reference collection checks remain independent correctness requirements.
Its properties receipt records the loaded collector identity; historical timings collected by another engine are not comparable.

The independent `:quality:component-benchmarks:jmhComponents` corpus uses the same 26 compiled component definitions as documentation and Fabric parity.
Set `-Pstrata.performance.repetition=0`, `1`, and `2` in independent invocations with the same Java, inputs and host conditions; preserve each `build/reports/jmh/components/run-<index>` directory.
`-Pstrata.performance.smoke=true` runs only the Row fixture with one short iteration, under a separate `components-smoke` output directory.
It verifies actual fork execution and receipt publication; it is not complete performance acceptance or a substitute for the three full invocations.
The default AverageTime mode records mean operation costs; its percentile-looking JMH fields are not per-operation latency quantiles.
Use `-Pstrata.performance.mode=sample` for JMH SampleTime distributions in separate `components-sample` directories.
The kit preserves JMH's raw sample histograms and aggregates each run's p50/p95/p99 separately, rather than calling AverageTime scores latency percentiles or pooling run-level quantiles.
It measures clean frames, viewport changes, pointer moves, and full create/attach/frame/close lifetimes through the real Minecraft-profile host.
Its synthetic images and bitmap resource-font inputs are prepared outside operations; this corpus does not replace loaded vanilla-font, remote-transport, or GPU measurements.
The historical `:quality:benchmarks:jmh` inputs, includes, execution settings, Java selection, and native-free dependency graph remain separate.
The component corpus is isolated in `quality:component-benchmarks`, so its native font bindings cannot change that historical runtime classpath.
The `component-api.tsv` registry assigns every actual public component entry point, including overloads and generated state-binding aliases, to a typed executable workload.
`:quality:component-benchmarks:check` compares the loaded API archive against that exact registry and verifies every registered idle/input/resize operation completes.
`:quality:component-benchmarks:captureComponentInventory` stages a prospective registry under the build directory for review; verification never rewrites its baseline.
The component registry covers these component entry points, rather than claiming complete coverage of every public SPI, resource provider, or remote host.

For local selection, pass `-Pstrata.performance.changedPaths=<UTF-8-path-list>` to the component check or JMH task.
The list contains repository-relative paths, one per line; known declaration and example owners select their feature union, and any unknown path selects the whole corpus.
An empty list selects no component operations, but still verifies the complete API registration.
Release verification omits this property and runs the entire corpus.

The `:quality:benchmarks:jmhHistorical` entry point records the unchanged historical Rendering, ReactiveRendering and OverlayRendering fixtures through the shared kit.
Its normal matrix contains 54 AverageTime cases; SampleTime uses a separate suite, and smoke collection is a separate one-case subset.
It inherits the existing JMH plugin task's Java launcher and retains its three one-second warm-up iterations, five one-second measurement iterations, one fork, one thread, microsecond units and GC profiler.
The original `jmh` task and its result path remain unchanged.
`:quality:benchmarks:check` verifies the actual generated historical matrix before either task is used.
Use the same repetition and mode properties and packaged summary/comparison commands as the component corpus.

JMH evidence requires an independent fork and rejects JVM flags that redirect the classpath, replace its classloader, patch modules or inject instrumentation agents.
Such flags could make parent-loaded artifact identities certify different child code; they fail before any output directory or success receipt is created.
Ordinary heap, GC and native-access JVM options remain supported and are recorded by JMH.
