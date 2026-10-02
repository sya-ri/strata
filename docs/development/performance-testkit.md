# Performance testkit

The `quality:performance-testkit` module owns performance collection and evidence contracts shared by Strata and downstream applications.
It is a test-only Maven publication in the Strata repository, with the same release version as the other modules.
Its JVM coordinate is `dev.s7a.strata:strata-performance-testkit`; Kotlin Multiplatform consumers use `strata-performance-testkit-multiplatform`.
The development branch can be published to an isolated local repository; the first official publication belongs to the next release, without replacing any existing release artifact.

Consumers supply their real components, stable input, actions, readiness predicates, and expected work.
The kit owns clocks, sample collection, distributions, native presentation counters, runtime monitoring, and collector provenance.
Application code must not add another timing engine or interpret unavailable collection as zero work.
Typed JVM detekt checks prohibit direct clocks and CPU/allocation/GC accounting in benchmark and server fixtures.
Kotlin/JS lacks type-resolved detekt analysis, so the `BrowserPerformanceOwnership` source rule reserves direct browser-clock, timestamp-callback and raw JavaScript names for the kit, including imported aliases and callable references.
It covers every helper in Web integration's `jsMain` and the shared canonical declaration directory, without restricting production animation clocks.
This is a source ownership policy, not an adversarial JavaScript sandbox or a proof of every possible dynamic access.
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
`JmhPerformanceEvidence.summarize` validates actual archives, raw-result digests, independent repetitions, controlled conditions and complete workload matrices before aggregating per-run medians.
`JmhPerformanceEvidence.compare` revalidates both raw suites and permits target-byte changes only within the same module/representative inventory.
Collector, harness, fixture, external inputs, workload matrix and measurement conditions must match.
Unavailable GC time and ratios against a zero baseline remain unavailable; absolute timings never determine success.

The JVM-only `:quality:performance-testkit:processEvidence` task reads one UTF-8 JSON request via `-Pstrata.performance.request=<request-file>`.
The request specifies `command` (`jmh-summary` or `jmh-comparison`), the actual `collector` JAR, a new `output` file, and either `runs` or `baseline`/`candidate` directory arrays.
`repetitions` defaults to three independent invocations.
The task runs `PerformanceEvidenceCli` from the packaged collector with its normal Kotlin/Gson runtime classpath and refuses to overwrite output.
An external application can call that same JVM entry point or the typed API using its resolved testkit artifact.

For controlled historical runtime comparisons, `:quality:benchmarks:jmhHistorical` accepts an optional `-Pstrata.performance.historicalRuntime=<UTF-8-properties-file>`.
The three keys are `\:api`, `\:runtime\:core` and `\:runtime\:headless`; values select distinct actual runtime JAR paths.
JDK Properties requires escaped colons in these project-path keys and escaped backslashes in Windows paths.
The task replaces only those three resolved project artifacts on the execution classpath, preserving the same compiled fixtures, collector, harness, external inputs and default 54-case matrix.
The kit verifies the classes actually loaded in the parent and forks, then preserves their archives rather than trusting the selected filenames.
Use `-Pstrata.performance.historicalOutputRoot=<new-directory>` for the other side's independent outputs; the ordinary historical output and defaults remain unchanged.
Execute both modes three times with repetition indexes 0–2 for each side and process `jmh-comparison` separately for each mode.
Older targets that cannot execute the unchanged fixture are failures, not permission to remove cases or loosen provenance checks.

```json
{
  "command": "jmh-summary",
  "collector": "/absolute/path/to/measured-testkit.jar",
  "output": "/absolute/path/to/new-summary.json",
  "runs": ["/absolute/path/to/run-0", "/absolute/path/to/run-1", "/absolute/path/to/run-2"]
}
```

## Evidence and work assertions

The root `verifyPublishedPerformanceInventory` gate compares [reviewed module/host registrations](../../gradle/performance-modules.tsv) against the actual Maven publication model.
Adding or removing a published project without updating its exact registration fails `check`; duplicate hosts, missing fixture source files and missing verification tasks also fail.
This is an entry-point registration check, not completed measurement evidence and not a substitute for exact member inventories.
Versioned Fabric registrations currently point to the shared loaded-client profile-cache probe, which already delegates its actual open/extraction measurements to `JvmPerformanceRunner`.
Those short correctness probes do not establish three full-default native performance repetitions or native work-counter coverage for every supported rendering family.
The test-only collector registers its own contract verification separately from runtime workload collection.

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

Evidence processing uses Java and the JVM testkit; Python is not a requirement and no Python compatibility package is shipped.
CPU and native reports use `LoadedArtifactMetadata` directly; historical field-name projections and distribution adapters are removed.
Consumers preserve the kit's distribution fields and unavailable values without translating them into an older schema.
`JvmPerformanceEvidence` rejects mismatched collectors, copied invocations, missing controlled conditions and duplicate phases.
Phase indexing returns canonical JSON text tuples, independent of parsed versus constructed number types and object property order.
Equivalent decimal spellings share one identity; distinct exact values, including integers beyond double precision, remain distinct.
Consumers use the shared index for both their declared matrix and raw reports; no numeric-key workaround belongs downstream.
The loaded collector identity is captured once and returned as a detached copy; replacing that archive later cannot certify new receipts.
`JvmPerformanceReports` validates raw phase reports against a consumer-declared `PerformanceReportContract`, aggregates registered metrics and compares complete image inventories.
Each contract declares the workload, exact phase count and identities, required report/phase conditions, and runtime fields permitted to vary between candidates.
Variant fields remain identical within each repetition group; sample counts are always controlled.
Applications supply fixture-specific assertions, controlled input declarations and workload annotations.
Report serialization and metric projections belong to the kit; consumers must not add independent report formatters, unit conversions or per-sample normalization.
`NativePerformanceEvidence` verifies actual local archive origins, representative resources and complete CPU/native class-tree agreement without filename/version assumptions.
Unsafe archives, missing inputs, non-finite measurements and invalid divisors reject success.
A missing optional measurement remains unavailable, never zero.
The JVM tests exercise these contracts against the actual packaged collector rather than a compiled-directory substitute.

## Host boundaries

| Entry point | Collection boundary |
| --- | --- |
| `JvmPerformanceSchedule` | One owner-thread operation per host callback; the kit owns warm-up, sampling, deadline and terminal release. |
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

The host enum identifies Paper and Velocity evidence, but an enum value does not prove that a real transport scenario has run.
Loaded Minecraft family verification, browser executions, and remote server/client measurements must be reported separately from JVM unit tests.

The optional Paper acceptance fixture enables real primary-owner measurements with the server JVM property
`-Dstrata.paper.performanceKit=<absolute-packaged-testkit.jar>`.
It requires the existing negotiated player and acknowledged presentation checks to finish first, then advances
`JvmPerformanceSchedule` once per server tick for owner entry, capabilities and a 100-node HUD open/close lifetime.
Each interval uses 30 warm-up operations and 60 samples; application assertions and provenance capture stay outside timing.
The fixture loads the selected unmodified collector through an isolated loader instead of bundling another copy into the plugin.
Folia movement and region-migration acceptance cannot enable this primary-owner fixture.
The measured open/close lifetime includes projection and plugin-message submission, but does not wait for native acknowledgement.

Prepare `:runtime:paper:pluginJar`, `:examples:paper:jar`, `:integration:paper:jar` and the testkit `jvmJar` with mise,
then run the verified exact-version Paper server and the existing Fabric acceptance task as separate JVM processes.
Pass a fresh UUID as `strata.paper.run` to both server and client and preserve the server's plugin archives, logs,
client receipts and the directory identified by `performanceDirectory` in `server.properties`.
No Python program is required for this performance path.
Both server fixtures also require `-Dstrata.server.performanceInputs=<absolute-UTF-8-properties-manifest>`.
Each manifest value is an absolute path to an actual configuration file, decoded by the kit's `JvmPerformanceInputs`.
Paper requires labels `server-properties`, `global-configuration` and `world-defaults` for `server.properties`, `config/paper-global.yml` and `config/paper-world-defaults.yml`.
Velocity requires `proxy-configuration`, `first-backend` and `second-backend` for the proxy's `velocity.toml` and each backend's `server.properties`.
Missing or extra labels fail before collection.
The input adapter uses JDK Properties setting values for `.properties` files, so auto-generated timestamp comments do not change the controlled input; other configuration formats retain exact byte identity.
Every original file hash is recorded independently through the kit, and changed settings or bytes during collection or subsequent processing reject success.
The processor requires identical controlled input identities across repetitions and verifies the original files in the still-preserved invocation directories.
Run the same pinned server, Java, collector, plugins and configuration three times with independent UUIDs.
The JVM-only `:integration:paper:processPerformanceEvidence` task consumes a UTF-8 request through
`-Pstrata.performance.request=<request-file>` containing `collector`, `output` and three `runs` performance directories.
It delegates report loading, invocation validation, phase aggregation and medians to `JvmPerformanceReports`,
while the fixture checks its three exact owner workloads and verifies the still-preserved actual plugin archives.
It fails if the processor fixture differs from the measured fixture or any archive has changed.
Successful unit tests of the loader and processor do not certify real server execution; loaded Paper and Velocity evidence remain separate.

The optional Velocity fixture enables post-backend-switch measurements with
`-Dstrata.velocity.performanceKit=<absolute-packaged-testkit.jar>` on the proxy.
Prepare `:runtime:velocity:pluginJar`, `:examples:velocity:jar` and `:integration:velocity:jar` alongside the two ordinary Paper backends.
Use the existing real proxy input/backend-switch transaction and pass `-Pstrata.velocity.performance=true` to the Fabric acceptance task.
Only the final performance-enabled readiness wait is extended; ordinary acceptance keeps its historical waits.
The fixture requires two negotiated HUD slots and acknowledged opening before measuring HUD close.
Its four intervals separately measure owner-entry queue submission, capabilities queue submission, closing a 100-node HUD and closing two concurrent 100-node HUDs.
Submission intervals do not measure queued execution, and close intervals do not include open, queue scheduling or client acknowledgement.
The shared schedule owns 30 warm-up operations, 60 samples and the interval deadline.
Every queued operation and expected lifecycle event must finish before the next opportunity or report publication.
Event threads enqueue snapshots; all fixture state and collector operations stay on the physical UI owner.
Failures and disconnects release live sessions, pending readiness and the isolated loader; partial interval files cannot certify a complete invocation.

Preserve three independent proxy/backend/client invocations and the `performanceDirectory` from each proxy receipt.
The JVM-only `:integration:velocity:processPerformanceEvidence` task consumes the same request shape as Paper.
Both entry points delegate to `ServerPerformanceEvidence`, which uses shared-kit validation and aggregation.
Every interval within one performance directory must belong to the same invocation, host, collector, environment and runtime archive identities; mixing individually valid intervals from different server invocations fails.
Both hosts also identify the actual host API archive, so changing the server/proxy distribution invalidates the controlled archive identity.
The summary contract is `strata-<host>-summary-v1`; previous host-specific summary envelopes are removed.
No Python runner is required: launch the pinned server/proxy JARs with Java, then run the existing Fabric task through Gradle and process the preserved directories through the JVM entry point.

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
It retains the reactive six-phase fixture and also compiles the same 26 typed component definitions used by JMH and Fabric rather than maintaining another set of component samples.
The compiled Web contract classifies every canonical definition exhaustively and exports its inventory during the normal site build.
Before sampling, the actual themed host must mount and release every admitted definition and explicitly reject every unavailable definition without retaining DOM children.
Admitted canonical definitions register Initial, Idle, Resize and Release; the reactive fixture additionally measures real state updates and pointer activation.
These canonical definitions do not exercise every overload or capability: for example, the shipped multiline Text definition is unavailable on Web while the reactive fixture measures supported single-line text.
Supply a new evidence file explicitly; the kit rejects an existing path before launching a browser and uses exclusive file creation to prevent concurrent replacement.

```powershell
mise.exe exec -- ./gradlew.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" "-Pstrata.web.performanceOutput=build/performance-verification/browser-run/browsers.json" :integration:web:measureWebPerformance
```

Each engine starts and closes a separate browser process for every repetition; the scenarios and phases within that invocation share one kit-owned run UUID.
The loaded browser version must remain identical across an engine's three invocations; a version change rejects the group and still closes the changed invocation.
The driver rejects duplicate engine/scenario/phase registrations and conditions that disagree with the collector's default 30 warm-up operations and 60 samples.
Fixture-returned data cannot replace the driver-owned invocation or host identity, and operation failure closes the current page and browser before rejecting the matrix.
`:quality:performance-testkit:verifyBrowserPerformanceDriver` tests these orchestration and failure contracts with synthetic browser lifetimes and is part of the kit's `check`; it is not real browser performance evidence.
The driver records the actual linked testkit JS artifact and driver hashes separately from the complete application-bundle hash, browser version, viewport, and workload conditions.
The kit captures input hashes before collection and verifies them again before writing evidence; an operation failure or changed input cannot produce a successful report.
It also hashes and rechecks separately supplied fixture-input files and compares the loaded browser inventory with the independently exported build inventory before sampling.
Every fixture registers its actual application-script URL; the kit hashes the response body fetched by the browser and rejects a response that differs from the captured bundle before sampling.
Applications supply artifact paths, engines, fixture URLs and operations rather than implementing hashing, collection or report writing themselves.
Before measurements, each browser verifies that operation and cleanup failures reject the interval and release the fixture exactly once.
Preparation, assertion, collector cleanup, and evidence writing are outside synchronous action timing.

The Minecraft resource-profile cache probe also uses the shared JVM runner, preserving its two explicit fresh extractions, eight warm opens, first open, and post-reload open.
Its pixel, reload, native-hook, immutable-ownership, and weak-reference collection checks remain independent correctness requirements.
Its properties receipt records the loaded collector identity; historical timings collected by another engine are not comparable.

The independent `:quality:component-benchmarks:jmhComponents` corpus uses the same 26 compiled component definitions as documentation and Fabric parity.
The complete Inventory and Social screen fixtures are not compiled into this portable component corpus; their native acceptance and rendered documentation remain separate.
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
The separate `runtime-api.tsv` registry preserves every JVM-visible public/protected symbol in the actual loaded API, core, Minecraft-profile and LWJGL font modules, including overloads and owners.
Both full and narrow component collection validate these registrations against the loaded archives and the actual generated component, stress and font workloads before selecting operations.
Registered benchmark methods have explicit idle, update, input, resize, preparation or release phases; an unknown method requires a reviewed phase assignment.
`:quality:component-benchmarks:captureRuntimeSurfaceInventory` stages a prospective baseline for review without rewriting the checked-in registry.
The binary inventory deliberately includes Kotlin internal declarations exposed publicly in JVM bytecode, so changing those declarations also requires a reviewed registry update.
Registration associates module surfaces with executable workload families; it does not establish that each member executes, or substitute for loaded Fabric, Web, remote, Paper or Velocity evidence.

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

`JmhForkProfiler` uses JMH's standard internal-profiler lifecycle to verify actual child-loaded target, collector, harness and fixture class trees, including generated benchmark classes, before sampling.
It checks registered external input hashes and owns no timer or application operation.
Every measured fork and iteration must return its provenance confirmation; a missing, failed or incomplete profiler rejects the success receipt and shared summary.
Generated `@Fork` arguments are checked alongside CLI overrides, so benchmark annotations cannot bypass the fork-classpath constraints.
Collector changes require fresh evidence; earlier receipts without child verification cannot satisfy this contract.

The separate stress corpus is selected with `-Pstrata.performance.stress=true` on `jmhComponents` and writes separate `stress` or `stress-sample` directories.
It measures 100/1,000,000-row indexed virtual lists, eight-image Canvas churn at 256/1024 pixels, configured text lengths of 32/16,384 UTF-16 units, actual checkbox pointer activation, explicit animation-cell time advances, 128/4096-observer fan-out, and 1/2/4-pixel nine-slice patterns with transparency.
These sizes are declared stress inputs, rather than claims about application limits or complete font-provider coverage.
Its 39 generated cases preserve the existing component and historical matrices; JMH and the shared kit own all collection and comparison.
The component `check` also asserts idle reuse, real changed work, bounded virtual-list nodes, input state and released source subscriptions.
All 1/2/4-pixel nine-slice patterns must match an independent modulo-based pixel oracle before and after a size update; the one-pixel case has a drawing-work ceiling, while multipixel cases have no minimum command count.
Command counts remain diagnostic measurements, so combining repeated pixels cannot fail admission merely by reducing work.
The full-image oracle applies to integer presentation; fractional transforms sample each slice through its own rounded floating-point destination.
A runtime boundary regression records a four-texel slice selecting a different texel from a merged logical image after shrinking, so an optimization must preserve that sampling contract rather than assuming that logical precomposition always has identical pixels.
Canvas pixel preparation and immutable profile/font loading remain outside the timed operations; native raster/upload/cache churn still needs loaded-client evidence.

The native-free `:quality:remote-benchmarks:jmhRemote` corpus measures real declaration diffs, patch validation, update/snapshot codecs and framing-owner lifetimes.
Its 30 generated cases use 100 nodes and the actual default 8192-node protocol bound, with stable, single-record and complete-record changes.
The module's `check` verifies exact changed-record counts, immutable tree parity, canonical encoded messages and byte-identical fragmented delivery for every combination.
Use the same three independent repetitions and separate AverageTime/SampleTime modes; smoke is a separate five-case input subset.
These are protocol CPU measurements; actual Paper/Velocity owner scheduling, plugin messaging, backend switches and multisession behavior require separate real host evidence.

The stress work gate explicitly requests 16,384 diagnostic records for the 4096-observer case, whose retained tree contains more nodes than observers.
The normal 4,096-record runtime diagnostic bound remains the default; a requested bound is finite and declared before monitoring starts, and overflow still rejects evidence.

The separate font corpus is selected with `-Pstrata.performance.fonts=true` on `jmhComponents` and writes independent `fonts` / `fonts-sample` directories.
Its 42 generated cases cover bitmap and Unihex with enabled/disabled raster caching, accepted 128-level and rejected 129-level reference graphs, STB and FreeType glyphs, 17 native face descriptors against 1/16-face bounds, and ICU mixed-direction shaping at 32/16,384 UTF-16 units.
The original CC0 geometric TTF fixture and all resolved control libraries are archived as explicit inputs; no operating-system font or fetched resource is substituted.
Source bytes, PNG encoding and ZIP preparation happen outside measurement. Snapshot loading has its own named operation; retained glyph resolution, cache/face churn, shaping and native-engine lifetimes are separate operations.
The untimed work gate verifies actual provider output, complete generated registration, finite retention ceilings, reference-limit rejection and zero retained faces/pixels after close.
These declared stress inputs do not claim exhaustive combinations of every configurable resource limit or GPU/native upload measurements.

JMH fixtures and server performance fixtures use the existing typed detekt gate with the standard `ForbiddenMethodCall` rule.
Direct JDK clocks, Kotlin timing helpers and CPU/allocation/GC MXBean collection are rejected, including resolved references and aliases; runtime input configuration remains allowed.
The gate uses the actual JMH source set or Paper/Velocity integration main source set, including shared declarations compiled into that same module, its classpath and selected Java toolchain.
It checks the shared server-performance sources and all Paper/Velocity fixture main sources, including helpers without a performance filename.
It does not inspect arbitrary external helper internals or unresolved calls.
An executable fixture's source and unit tests are separate from successful real host evidence.
The Web driver and portable fixtures still delegate their measurements to the kit; this JVM type-resolution gate does not claim to enforce JavaScript property access.

The historical and remote work gates also compare the complete loaded headless and remote protocol binary surfaces against exact checked-in `headless-api.tsv` and `remote-api.tsv` registries.
Their capture tasks (`captureHeadlessInventory` and `captureRemoteInventory`) stage prospective registrations for review without modifying verification baselines.
Headless members register the existing rasterization and translucent-composition cases; remote members register the existing stable and changed declaration/codec/framing cases.
Full and smoke collection validate these surfaces first, and archive their reviewed registry as an explicit control input.
The historical 54-case and remote 30-case matrices and their execution defaults remain unchanged.
This is module registration backed by actual workload families, rather than proof that every member executes or a substitute for real remote transport evidence.

The separate retained-remote corpus uses `-Pstrata.performance.remoteSessions=true` on `jmhRemote` and independent `remote-sessions` / `remote-sessions-sample` output directories.
Its nine cases use real `RemoteServerSession` owners at one 100-node session, one session at the current 8192-node bound, and 16 shared-source sessions with 512 nodes each at the same aggregate bound.
It measures unchanged projection, real shared revisions and complete create/attach/update/close lifetimes, with one current outbound message per peer and no output history.
The work gate checks exact node and changed-record counts, no unchanged declaration traffic, unchanged peer subscriptions during an independent lifetime, and zero retained nodes/subscriptions after close while handles remain reachable.
The 8192-node and 16-HUD defaults are verified against the loaded protocol limits rather than silently assuming future limits remain unchanged.
These native-free retained owners do not replace real Paper/Velocity scheduling, plugin messaging, backend switching or negotiated native presentation evidence.
