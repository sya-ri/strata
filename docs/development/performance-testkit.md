# Performance testkit

The `performance-testkit` module owns performance collection and evidence contracts shared by Strata and downstream applications.
It is a test-only Maven publication in the Strata repository, with the same release version as the other modules.
Its JVM coordinate is `dev.s7a.strata:strata-performance-testkit`; Kotlin Multiplatform consumers use `strata-performance-testkit-multiplatform`.
It is available starting with Strata 0.2.2; development candidates can be published to an isolated local repository without replacing any existing release artifact.

Consumers supply their real components, stable input, actions, readiness predicates, and expected work.

## Quick investigation

Use `-Pstrata.performance.quick=true` while finding candidates or checking an intermediate change.
`PerformanceProfile.Quick` caps native and synchronous JVM intervals at three warm-up operations, ten samples and one independent execution, preserving smaller intervals and readiness deadlines.
Its viewport selection keeps one middle entry of the registered matrix: GUI scales 1–4 select scale 3, while three CPU viewports select the middle viewport.
Consumers retain every explicitly selected workload; without a selection, the screen runners choose one registered case.
Reports use a separate `-quick` workload identity and require an explicit quick request for processing.
The standard profile preserves existing sample counts, repetitions and complete viewport matrices; quick receipts cannot satisfy those acceptance contracts.

For component, historical and remote JMH, the same Gradle flag uses no warm-up iterations, one 100 ms measurement iteration and one fork, under separate `*-quick` directories.
With no explicit selection, the ordinary corpora use their existing smoke input subsets. Explicit workloads and historical parameter selections are retained; the independent raster corpora keep their selected input matrix.
The original smoke option and every standard JMH configuration remain unchanged.
Use `-Pstrata.performance.benchmarks=StressRenderingBenchmark -Pstrata.performance.parameters=<file>` with `workload=FanOut4096` in the parameter file to investigate that stress case through `:quality:component-benchmarks:jmhComponents`.
Smoke scores remain exploratory and are not compared with warmed standard measurements.

For a loaded client, `./gradlew benchmarkMinecraftQuick -Pstrata.performance.nativeOutput=<fresh-directory>` selects one native component on the newest version in the repository's verified target catalog.
Run this entry as a standalone task; combining it with other tasks fails before execution so quick selection cannot narrow correctness acceptance or share its output directory.
Set `-Pstrata.minecraftVersions=<exact-version>` to choose a different single supported version and `-Pstrata.performance.workloads=TextField,NativeCanvas` to keep multiple explicit cases.
The task runs the performance entry only, without the ordinary correctness scenes; it does not claim support for an unmeasured version.
Add `"quick": true` to a native summary request and provide one run plus a real CPU receipt for binary provenance, as for standard collection.
Before accepting a performance change, repeat the affected workloads using their unchanged standard settings and run the required correctness gates once the implementation is stable.

The separate `FrameClipPerformanceEvidence` entry registers `FrameClipBenchmark` with the shared collector and accepts a fresh output directory, repetition index and ordinary JMH CLI settings.
Supply the actual API, core, headless, Minecraft, font and Fabric runtime archives on the normal application/context classpath, alongside the unchanged generated fixture, collector, harness and control libraries.
Register external control libraries with the existing `strata.performance.inputs` manifest and select the same mode through `strata.performance.mode` and JMH's `-bm` argument.
The complete matrix contains 126 rows per mode and is collected three independent times per side using standard settings.
Archive and full class-tree identities from the actual parent and forks establish the comparison; filenames and rewritten provenance reports do not.
Complete native presentation and GUI-only GPU measurements remain a separate acceptance scope.

## Collection ownership

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

The JVM-only `:performance-testkit:processEvidence` task reads one UTF-8 JSON request via `-Pstrata.performance.request=<request-file>`.
The request specifies `command` (`jmh-summary` or `jmh-comparison`), the actual `collector` JAR, a new `output` file, and either `runs` or `baseline`/`candidate` directory arrays.
`repetitions` defaults to three independent invocations.
The task runs `PerformanceEvidenceCli` from the packaged collector with its normal Kotlin/Gson runtime classpath and refuses to overwrite output.
An external application can call that same JVM entry point or the typed API using its resolved testkit artifact.

Both `:quality:benchmarks:jmhHistorical` and `:quality:component-benchmarks:jmhComponents` select generated fixture classes through `-Pstrata.performance.benchmarks=<comma-separated-class-names>`.
Nested fixtures accept either Java source names (`Outer.Inner`) or JVM binary names (`Outer$Inner`); collection and fork certification use the exact generated JMH method names.
Use exact qualified names or unambiguous simple names; empty, duplicate, unknown and ambiguous names fail before timing.
Omitting this option preserves each task's original default corpus.
Class selection uses anchored JMH include filters and checks the complete generated method/parameter matrix, so similarly named supplemental fixtures cannot enter a selected corpus implicitly.
`strata.performance.suite=<name>` chooses a separate output label without adding a corpus-specific build flag.
Quick collection retains explicitly selected classes, methods and parameters; ordinary quick runs keep their existing default smoke subset.

Add a JMH fixture to the existing source set without changing a Gradle build script or collection launcher.
An optional public static no-argument `verifyWork()` method owns deterministic fixture acceptance outside timing; Kotlin companions expose it with `@JvmStatic`.
Ordinary checks discover every generated fixture and its optional verifier automatically; selected collection also checks the chosen fixtures before starting forks.
Keep expected case counts and fixture-specific pixel, work and lifetime assertions with the fixture or its work helper.
Additional external files use `-Pstrata.performance.fixtureInputs=<UTF-8-properties-file>` with unique labels and absolute regular-file paths.
The collector archives these files alongside resolved control libraries and rejects overlapping labels; no new fixture-specific Gradle property is needed.
For parameter subsets, `strata.performance.parameters` uses parameter names as keys and comma-separated compiled values as values.

Use `-Pstrata.jvmOnly=true` for fully qualified JVM fixture preparation and collection tasks.
This model includes the runtime, testkit and quality dependency closure of the three JVM benchmark modules without versioned Fabric projects or Web applications.
It accepts `formatKotlin` (including the native font backend), JVM tests and archives, ABI checks, `jmhClasses`, `jmhRunBytecodeGenerator`, `jmhCompileGeneratedClasses`, `jmhHistorical`, `jmhComponents`, `jmhRemote` and `processEvidence`.
Selection, external-input preservation, generated-work verification and loaded-archive certification inside each independent JMH fork are unchanged.
The scope describes preparation and collection, not completed acceptance: `check`, publication, aggregate Kover and published-host inventory tasks require the complete model and fail at settings when combined with the flag.
Do not combine a scoped flag with IDE/Qodana import, `strata.completeIdeaModel`, Minecraft target selection or another scoped flag.
Without a scoped flag, combining collection with ordinary correctness tasks preserves the complete model.

For controlled historical runtime comparisons, `:quality:benchmarks:jmhHistorical` accepts an optional `-Pstrata.performance.historicalRuntime=<UTF-8-properties-file>`.
The three keys are `\:api`, `\:runtime\:core` and `\:runtime\:headless`; values select distinct actual runtime JAR paths.
JDK Properties requires escaped colons in these project-path keys and escaped backslashes in Windows paths.
The task replaces only those three resolved project artifacts on the execution classpath, preserving the same compiled fixtures, collector, harness, external inputs and default 54-case matrix.
The kit verifies the classes actually loaded in the parent and forks, then preserves their archives rather than trusting the selected filenames.
Use `-Pstrata.performance.historicalOutputRoot=<new-directory>` for the other side's independent outputs; the ordinary historical output and defaults remain unchanged.
Execute both modes three times with repetition indexes 0–2 for each side and process `jmh-comparison` separately for each mode.
Older targets that cannot execute the unchanged fixture are failures, not permission to remove cases or loosen provenance checks.

The separate `-Pstrata.performance.benchmarks=NonuniformOverlayBenchmark -Pstrata.performance.suite=nonuniform-overlay` family measures prepared mixed-alpha/RGB images followed by 1, 16 or 64 full-area translucent fills at 320×180 and 1920×1080.
It uses the same JMH defaults, controlled-runtime selection and shared evidence processing, with its own six-case fixture registration and `nonuniform-overlay` output directory.
Source preparation is outside measurement; each operation allocates a fresh output and preserves every ordered blend.
This family does not change the historical 54-case matrix or satisfy its acceptance.

The separate `-Pstrata.performance.benchmarks=SampledRasterBenchmark -Pstrata.performance.suite=sampled-raster` family measures fractional portable image generation with solid opaque, solid translucent and 64×64 patterned sources, each with an opaque non-identity RGB tint.
Its 12 cases keep physical output at 320×180 or 1920×1080 while varying final density between one and four.
Prepared immutable inputs exclude resource decoding; each operation allocates a fresh raster.
It retains the same JMH defaults, actual loaded-runtime selection and shared evidence processing, writes `sampled-raster` outputs, and leaves every historical workload unchanged.
Select it independently of smoke or nonuniform overlays, collect each mode three times per runtime side, and compare matching fixture and collector identities.

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
This entry-point registration check is paired with `:quality:component-benchmarks:verifyPublishedHostInventory`.
The member gate first requires every published project's actual `checkKotlinAbi` task, then uses `CompilerApiInventory` and `PerformanceInventory` to reject new, removed or unassigned compiler declarations for each reviewed physical host.
Both gates remain mandatory in ordinary complete-model `check` before review; isolated collection does not rerun publication-wide acceptance.
Run `:quality:component-benchmarks:check` without a scoped flag on the revision being reviewed, in addition to the full verification boundary described in [build and verification](build.md).
`:quality:component-benchmarks:capturePublishedHostInventory` stages prospective assignments for review without changing the checked-in registry.
Compiler source visibility is distinct from the separate loaded-JVM inventories; those origin checks remain required.
Registration is not completed measurement evidence or proof that every member executed.

## Native component presentation

During an optimization, repeat the affected CPU corpus and focused JVM correctness checks.
Run native performance invocations separately after the candidate stabilizes, and run the full supported-version regression matrix once at the final verification boundary.
If a later failure requires another implementation change, return to affected checks and performance evidence before repeating the final regression gate.
Correctness-only clients use resource-aware admission that adapts during execution as described in [CI execution](ci.md); measurement clients remain exclusive on their machine.

Use `./gradlew benchmarkMinecraft -Pstrata.performance.nativeOutput=<new-absolute-directory>` for standard native collection on the newest version in the repository's verified target catalog.
Set `-Pstrata.minecraftVersions=<exact-version>` to select another single supported version.
A standalone `benchmarkMinecraft` or `benchmarkMinecraftQuick` invocation with this explicit version configures only its versioned adapters; omitted versions, mixed task requests and complete IDE models preserve their existing project inclusion.
The production Fabric GameTest collects the 26 compiled canonical component declarations and the real sampled/custom Canvas scene by default; `-Pstrata.performance.workloads=<comma-separated-IDs>` selects the affected cases at all four GUI scales.
For example, `./gradlew benchmarkMinecraft -Pstrata.minecraftVersions=26.3 -Pstrata.performance.sampledImages=true -Pstrata.performance.workloads=SampledStationarySmall,SampledOrderedRowsLarge,SampledScrolledRowsLarge -Pstrata.performance.gpuQueries=true -Pstrata.performance.nativeOutput=<new-absolute-directory>` collects a control case plus overlapping and scrolling images.
Run the entry as a standalone task; it rejects other requested tasks and the quick profile, and requires a fresh output directory.
Invoke it three times with independent directories and the same selection for each baseline and candidate, with no other builds or performance workloads running during sampling.
The explicit `:integration:minecraft-fabric-<version>:runProductionClientGameTest` entry remains available for existing automation.
The native fixture delegates preparation, 30 warm-up frames, 60 complete operation frames per phase, runtime diagnostics and presentation counters to `MinecraftPerformanceMeter`.
It requests 1920×1080 at GUI scales 1–4 with Vsync disabled and a 120 FPS limit, verifies actual window/options on every frame, restores pacing and viewport afterward, and saves PNGs outside measurement.
Legacy GLFW performance windows temporarily remove decorations so a full-height framebuffer fits the desktop; the previous decoration state is restored independently of viewport cleanup.
The 108-phase corpus measures settled presentation; it does not measure input, mutation, resize, release latency or GPU completion.
Terminal native resource release is a correctness assertion outside timing.
The ordinary Canvas acceptance path remains unchanged when the property is absent.
Each invocation keeps a separate client directory beneath its integration project's `build/run/native-performance/`, preserving the actual processed-mod code sources needed for later archive/class-tree verification and satisfying the existing client-run containment contract.
Do not delete these client directories before processing or replace their origins with standalone Maven files.
For legacy production clients, window validation resolves intermediary client/window owners and descriptors through the actual Fabric mapping resolver; development-only class names are not assumed in a remapped client.
Missing mappings, missing host members, dead handles and iconified windows reject collection rather than producing a valid zero-cost interval.

Process the three reports with `:quality:component-benchmarks:processNativeComponentEvidence -Pstrata.performance.request=<UTF-8-JSON-request>`.
The request supplies `collector` (the processor's actual loaded testkit JAR), `runs` (three `report.json` paths), a new `output`, and `cpu_report` (an actual JVM report with the same runtime binaries).
For selected collection, supply the same comma-separated IDs as `workloads`; sampled-image collection also requires `sampled_images: true`.
Selected standard evidence keeps the default warm-up, sample counts and three independent invocations, but certifies only its declared workloads rather than full-suite acceptance.
That JVM report supplies loaded archive/class-tree provenance only; its measurements are neither synthesized nor compared with native latency.
The adapter selects the four shared API/core/Minecraft/font representatives from its real metadata, while Fabric remains native-only.
The shared kit validates collectors, independent invocations, registered conditions, exact phase matrices and actual CPU/native archive/class-tree bytes, then aggregates declared metrics.
The adapter additionally verifies the fixture archive, preserved PNG bytes, exact complete-frame counts and balanced native release.
Only invocation-specific output/terminal-receipt arguments are excluded from controlled JVM arguments.

Rendering/extraction-family representatives are 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1, 26.2 and 26.3, selected from the existing target declarations.
These ten tuples cover the current UI, Canvas and test-extraction families; they do not certify all combinations of transport, input, Java compatibility or resource loading across the 22 supported targets.
The exact case registry is `quality/component-benchmarks/src/main/resources/native-components.tsv`; a changed canonical matrix requires review rather than an automatic baseline update.
Fixture implementation or registration alone is not completed three-run native evidence.
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
An explicit null optional measurement, including any null distribution ancestor along a registered path, remains unavailable, never zero.
Missing fields and non-object ancestors remain malformed evidence.
The JVM tests exercise these contracts against the actual packaged collector rather than a compiled-directory substitute.

## Independent sampled-image, GPU and cold-image evidence

Set `strata.performance.sampledImages=true` together with a fresh `strata.performance.nativeOutput` to collect the independent sampled-image corpus.
Its reviewed registry is `quality/component-benchmarks/src/main/resources/native-sampled-images.tsv`: stationary, translated, resized, clipped, replaced, ordered overlapping-row, scrolling-row and tiled-translation scenes, each with 16, 64 and 256 texel source extents.
Standard collection retains 30 warm-up frames, 60 measured frames, three independent processes and GUI scales 1–4; quick collection retains its separate identity and counts.
Replacement inputs are distinct immutable identities prepared before sampling; source construction and PNG persistence stay outside extraction.
Ordered rows alternate between 32 and 64 overlapping samples, splitting tint and alpha-cutoff fallbacks over changing destination coverage.
Scrolling rows retain 64 ordered samples under a moving clipped layout; tiled translation moves 64 small 16-pixel logical outputs independently of source resolution.
The canonical 108-phase component corpus and its default selection are unchanged.
Supply `sampled_images: true` in the processor request for this corpus; explicit `workloads` selects only identifiers from its registry, and the processor rejects using these reports as canonical component acceptance.

Native payload counters distinguish successful source-image, CPU-raster and sampling-metadata uploads in RGBA8 bytes.
GPU-generated output and texture reuse add no CPU-upload payload.
Fallback counters distinguish unsupported tint composition, unsupported cutoff after identity tint, and other ineligible causes; capacity remains a separate existing counter.
Classification uses the compiled adapter's admitted effects before recording a reason, so an exact channel mask or cutoff rejected by clipping or capacity is not labeled unsupported tint or cutoff.
The other category includes unsupported mapping, clips and adapter/source limits and does not infer a more specific reason.
Older measured runtimes expose unavailable payload values as null rather than zero.

GPU queries are opt-in through `strata.performance.gpuQueries=true`, recorded in the controlled report conditions.
The compiled RenderPearl fixture records timestamp pairs immediately around the real native GUI consumer and uses the actual device's timestamp period to convert ticks to nanoseconds.
`GpuPerformanceMeter` requires every requested pair to complete before publishing p50, p95 and p99 GPU distributions.
The GPU scope includes all host commands between those timestamps; it excludes CPU preparation, uploads and sampled-target passes recorded before GUI consumption.
A separate owner-operation-to-first-observed-GUI-completion distribution includes CPU work, queueing and polling delay and supplies an upper bound at that host observation cadence.
Neither measurement certifies swapchain presentation, input-to-display latency or FPS.
Queries and their callbacks are owned by the fixture, bounded by the sample count, and completed and released outside measurement.
Other compiled families and disabled query collection explicitly report unavailable GPU measurements.
Compare repetitions only with matching version, backend, driver/device description, viewport, pacing, query mode and archive identities; different devices or backends remain separate evidence, not an interchangeable speed ratio.

`ColdImageBenchmark` is a separate six-case JVM corpus selected with `strata.performance.benchmarks=ColdImageBenchmark` on `:quality:benchmarks:jmhHistorical`.
It measures opening a fresh input stream, PNG decoding and immutable pixel acquisition separately from deterministic PNG encoding at 64, 256 and 1024 texels per axis.
The encoded input is resident before measurement, and PNG encoding returns fresh bytes without filesystem persistence; these boundaries do not claim an operating-system file-cache or network-cold workload.
The historical, sampled-raster and native steady-presentation definitions and defaults remain unchanged.

The independent `PortableTextBenchmark` corpus is selected with `strata.performance.benchmarks=PortableTextBenchmark` on `:quality:component-benchmarks:jmhComponents`.
Its twelve phases separate fresh host/layout/glyph extraction from CPU composition of prepared detached glyph commands over alternating opaque destinations.
The existing multilingual bitmap source and original geometric TrueType fixture at 64 and 256 logical pixels run at densities one and four with fixed full-HD output.
Font acquisition and snapshot decoding stay outside both operations; the extraction phase includes creation and terminal close of its fresh font owner, while composition owns a new raster each time.
This corpus leaves the canonical component, font-provider and historical matrices unchanged and records the actual headless, font and shared runtime binaries plus the registered original font file.

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
Whole operation-frame intervals start after untimed readiness, evidence capture and application snapshots, then end at the next before-presentation callback.
Success waits for the final operation frame's following boundary; it does not substitute a preceding warm-up frame or a zero interval.
Native extraction counters and diagnostics still cover exactly the requested measured extractions, without another application action during finalization.
Complete frames include diagnostic instrumentation and frame pacing; neither wall intervals nor render-thread CPU certify GPU completion or uninstrumented application latency.
Closing a meter clears application callbacks, screen references, monitor ownership, and bounded sample storage.

Window readiness uses the loaded host's `isIconified` contract when present, including SDL hosts.
Only the verified legacy `getWindow` family uses GLFW attributes; an opaque modern handle is never passed to another window backend.
Loaded correctness suites share one backend-aware viewport boundary for screen, font, transport and Canvas checks.
OpenGL retains Fabric's physical resize operation; Vulkan changes logical and framebuffer test extents on the render thread without resizing the platform window or swapchain.
Measured native workloads keep their fixed physical viewport; this correctness-fixture boundary does not change their inputs or sampler.
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
mise.exe exec -- ./gradlew.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" "-Pstrata.webOnly=true" :performance-testkit:formatKotlin :performance-testkit:updateKotlinAbi
mise.exe exec -- ./gradlew.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" "-Pstrata.webOnly=true" "-Dmaven.repo.local=<isolated-repository>" :performance-testkit:check :performance-testkit:publishToMavenLocal
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
`:performance-testkit:verifyBrowserPerformanceDriver` tests these orchestration and failure contracts with synthetic browser lifetimes and is part of the kit's `check`; it is not real browser performance evidence.
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

For iterative fixes, the default component corpus accepts `-Pstrata.performance.workloads=<comma-separated-IDs>` through the shared `PerformanceSelection` contract.
For other compiled fixtures, select classes with `strata.performance.benchmarks`, method IDs with `strata.performance.workloads`, and compiled parameter values with `strata.performance.parameters`.
For example, select `StressRenderingBenchmark` with a parameter file containing `workload=TextField32,TextField16384`, or `workload=NineSlice1,NineSlice2,NineSlice4` for tiled-image work.
Unknown, empty and duplicate IDs fail before collection; the complete API registration is still verified.
Selected JMH runs use separate `*-selected` directories and their exact generated matrix remains bound to the evidence receipt.
Keep all phases of the selected workloads, default warm-up/sampling settings and three independent invocations when comparing a baseline and candidate.
Class and method selection also apply to the font fixtures; their declared parameter subsets use the same parameter manifest.

The same option selects loaded native cases such as `TextField,NativeCanvas` at all four GUI scales.
The native performance entry prepares the actual resource profile and then collects directly, without running ordinary profile-reload, input, inventory and pixel-regression scenes on every measurement invocation.
Omitting the option retains the full 108-interval matrix and its existing acceptance contract.
A proper subset uses the distinct `native-components-selected-presented-v1` workload ID; pass the same comma-separated IDs as `workloads` in the native summary request.
The processor rejects missing scales, duplicates, leaked native ownership and a selection that disagrees with the request; targeted evidence cannot satisfy full-suite acceptance.

During optimization, run only affected workload phases and the deterministic parity/invalidation checks needed by that change.
Reuse completed unchanged evidence rather than restarting unrelated suites after each edit.
Common changes with unknown impact need a broader representative matrix; explicit selection must record that limited scope.
After the candidate is stable, collect the final required performance matrix serially and run the full ordinary GameTest acceptance once.
Automatic correctness-client concurrency does not apply to performance collection.

Retained remote collection also accepts workload IDs such as `Shared16At512` with `strata.performance.remoteSessions=true`.
Protocol collection accepts compiled method IDs such as `RemoteProtocolBenchmark.diff` through the same shared selection contract, retaining both declared node counts and all three change patterns for each selected method.
Omitting selection preserves its full 30-case matrix; unknown, duplicate or empty method IDs fail before collection, and selected evidence cannot satisfy full-suite acceptance.
Every selected corpus rejects unknown method IDs and unsupported parameter selections before collection.
The historical shared-kit entry accepts method IDs such as `OverlayRenderingBenchmark.composition` and an optional `strata.performance.parameters` UTF-8 properties file containing compiled JMH parameter subsets, for example `width=320,1920`, `layers=1,64` and `monitoring=false` on separate lines.
These selected runs have independent `*-selected` directories; omitting both options preserves the formal 54-case historical matrix and the original `jmh` task.
`strata.performance.output` supplies a fresh invocation directory for component or remote evidence; historical collection keeps its existing `strata.performance.historicalOutputRoot` directory option.

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

The separate stress corpus is selected with `-Pstrata.performance.benchmarks=StressRenderingBenchmark -Pstrata.performance.suite=stress` on `jmhComponents` and writes separate `stress` or `stress-sample` directories.

The supplemental `-Pstrata.performance.benchmarks=ExceptionalTextFieldBenchmark -Pstrata.performance.suite=exceptional-text` corpus uses public TextField operations with fixed synthetic TrueType metrics for signed fractional spacing, inexact large cancellation and infinite tails under both native rounding contracts.
Its 18 independent idle/update/lifecycle cases use the unchanged JMH defaults and shared processing, with `exceptional-text` / `exceptional-text-sample` outputs; it does not change the existing stress matrix or font inputs.
Resource preparation stays outside sampling, and its detached empty glyphs measure width/control work rather than native font rasterization.
`verifyExceptionalTextWork` checks every combination's clean reuse, changed public value and semantics, equal-metric pixel stability and complete font-resource release without elapsed-time thresholds.
Its compiled `ExceptionalTextWorkload` values can be selected through the generic parameter manifest.
The stress corpus measures 100/1,000,000-row indexed virtual lists, eight-image Canvas churn at 256/1024 pixels, configured text lengths of 32/16,384 UTF-16 units, actual checkbox pointer activation, explicit animation-cell time advances, 128/4096-observer fan-out, and 1/2/4-pixel nine-slice patterns with transparency.
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

The separate font corpus is selected with `-Pstrata.performance.benchmarks=FontProviderBenchmark,FontTextBenchmark -Pstrata.performance.suite=fonts` on `jmhComponents` and writes independent `fonts` / `fonts-sample` directories.
Its 42 generated cases cover bitmap and Unihex with enabled/disabled raster caching, accepted 128-level and rejected 129-level reference graphs, STB and FreeType glyphs, 17 native face descriptors against 1/16-face bounds, and ICU mixed-direction shaping at 32/16,384 UTF-16 units.
The original CC0 geometric TTF fixture and all resolved control libraries are archived as explicit inputs; no operating-system font or fetched resource is substituted.
Source bytes, PNG encoding and ZIP preparation happen outside measurement. Snapshot loading has its own named operation; retained glyph resolution, cache/face churn, shaping and native-engine lifetimes are separate operations.
The untimed work gate verifies actual provider output, complete generated registration, finite retention ceilings, reference-limit rejection and zero retained faces/pixels after close.
These declared stress inputs do not claim exhaustive combinations of every configurable resource limit or GPU/native upload measurements.

JMH fixtures and server performance fixtures use the existing typed detekt gate with the standard `ForbiddenMethodCall` rule.
Direct JDK clocks, Kotlin timing helpers and CPU/allocation/GC MXBean collection are rejected, including resolved references and aliases; runtime input configuration remains allowed.
The typed gate also rejects Kotlin monotonic time marks and elapsed-duration reads, including aliases and callable references, rather than only inline timing helpers.
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
