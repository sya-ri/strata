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

`BlitTemplateBenchmark` supplies 45 separate initial-frame, paint-rebuild and retained-clean boundaries across 15 compiled dense and ineligible controls.
Atlas cases use multiple source rectangles from one immutable source image; the distinct-image case deliberately retains the original command path.
Small/large sources and templates, cropped edges, one/four/32 groups, sparse/overlapping/reversed/stretched/oversized inputs, clips and fractional child transforms retain complete original geometry and straight ARGB.
Prepared source pairs are outside timing, while initial frames include actual session creation, attach, first paint and close; rebuilds change the source at its ordinary capture/commit cutoff.
The optional verifier compares original and actual presented pixels at physical densities one and two, immutable old frames, public copy/equality/hash, clean identity, bounded templates and balanced bindings.
It emits each case's actual command, template-pixel, paint and binding diagnostic to the preserved untimed work-gate output.
Use the existing historical shared-kit selectors and controlled API/core/headless runtime archives; the fixture adds no central registry or selector and reports no native uploads, GPU duration or FPS.
Private-copy payload removed by owned transfer is a source-derived count from each accepted template's pixel area, separate from measured CPU time and normalized allocation.

The component task accepts `-Pstrata.performance.profilers=<comma-separated-JMH-profiler-identifiers>` for additional metrics from standard JMH profilers.
It appends the requested profilers to the unchanged GC and loaded-fork provenance profilers; omission preserves the existing collection.
Use the same selection on both runtime sides and retain it in the raw receipt's arguments.
JMH resolves each identifier and rejects unavailable profilers before a successful collection receipt.

Use `-Pstrata.jvmOnly=true` for JVM tests, fixture verification, preparation and collection.
This model includes the runtime, testkit and quality dependency closure of the three JVM benchmark modules, the API-only JVM integration consumer, and the Velocity API/runtime owners, without versioned Fabric projects or Web applications.
Gradle resolves the registered tasks, selectors, abbreviations and task options normally, including `--tests` on typed JVM Test tasks and complete fixture verification such as `verifyHistoricalWorkloads` and `verifyComponentRenderingWork`.
The scope requires no task, test or fixture whitelist; missing tasks and projects retain Gradle's normal failure behavior.
Selection, external-input preservation, generated-work verification and loaded-archive certification inside each independent JMH fork are unchanged.
The scope describes preparation and collection, not completed acceptance: `check`, publication, aggregate Kover and published-host inventory tasks require the complete model and are rejected when combined with the flag.
The guard inspects the actual resolved task graph, so an option value cannot be mistaken for a complete-acceptance task.
Complete-acceptance tasks opt out of configuration caching in the scoped model, so later requests reconfigure and are refused before any task executes.
Do not combine a scoped flag with IDE/Qodana import, `strata.completeIdeaModel`, Minecraft target selection or another scoped flag.
Without a scoped flag, combining collection with ordinary correctness tasks preserves the complete model.

The generated `TextAreaInputBenchmark` fixture adds a separate 60-case logical editing corpus through the existing component entry point:
`:quality:component-benchmarks:jmhComponents '-Pstrata.jvmOnly=true' '-Pstrata.performance.benchmarks=TextAreaInputBenchmark' '-Pstrata.performance.suite=text-area-input'`.
Its 30 input rows cover beginning/middle/end primary presses on short wrapped, long BMP, supplementary, plateau and signed-coordinate lines, vertical/page navigation between two long hard-break lines, and three composition mapping regions.
Each source respects the default state capacity; middle/end unwrapped inputs preserve real caret-following horizontal pan.
Six initial-layout rows include fresh host creation, attach, first frame and terminal close with resource bytes prepared, while six clean-frame rows retain the existing frame.
Eighteen isolated lookups cover all six source/metric families and three positions through one prebound primitive method handle, with the same handle cost on both runtime sides.

Each input operation includes the fixed public opportunity restoration, settled frame and target dispatch in one cycle: a clean frame before an ordinary press, an opposite navigation key plus frame before a directional key, or Home/frame/preedit/frame before composition cancellation.
Measured JMH elapsed time and normalized allocation therefore share the same complete cycle boundary; preedit layout replacement is included and cannot be attributed to isolated dispatch.
The fixture uses no invocation-level setup, so it avoids per-call setup timestamps and allocation that the iteration-scoped [JMH GC profiler](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java) would include outside a dispatch-only elapsed-time interval.
Reflection, independent oracle scans, font/backend construction and resource generation remain outside the cycle; all pointer/key events are prebuilt.
The independent lookup rows isolate the actual line method, while complete initial-layout rows separately expose new-line admission and ownership costs.
Do not interpret the cycle score as single-event latency or subtract noisy control scores to invent a dispatch-only allocation result.
The untimed verifier independently checks complete Long-distance winners, scalar offsets, scroll/pan, focus token identity, committed semantics, exact headless pixels at scales one through three, clean frame reuse and terminal resource release.
Candidate coordinate-read checks use the loaded runtime's inlined search diagnostic; an older runtime without that diagnostic still executes the same complete winner oracle.
Source-derived baseline call/read counts must be labeled separately from observed diagnostics and measured CPU/allocation.

For a paired runtime comparison, freeze the fixture, its Java primitive-handle helper, generated JMH classes/resources, collector, harness and all common runtime/control archives before either side runs.
A fixture-only archive can combine the Kotlin and Java compiled fixture roots and generated JMH roots without embedding any target runtime classes; this binds the helper to the same preserved fixture code source.
Use the existing packaged collector entry point and the same resolved execution classpath, replacing only the selected actual Minecraft runtime archive.
Preserve actual parent/fork loaded archives and class trees, external inputs, source revisions, conditions, all 60 rows and three independent Standard receipts for each side.
Keep small-line, signed fallback, clean-frame and initial-layout regressions visible, and compare through the shared `jmh-comparison` processor.
The corpus makes no native latency, upload, FPS or GPU claim.

`TextLineLayoutBenchmark` keeps all 60 retained construction opportunities and 22 clean, caret and composition controls.
Its optional `operationReport()` uses the same original scene setup and benchmark methods through the shared owner-thread meter.
Initial construction replaces a keyed component in the primed host/font owner; it does not include a new backend or profile.
Edit and reflow samples retain the complete public mutation/frame opportunity; caret and composition controls retain their original restoring input cycles.
Independent scalar, exact bounded pixel, original-viewport old-frame and release oracles run outside samples, and terminal cleanup must succeed.
All 82 owner rows retain CPU, owner/all-thread allocation and wall metrics with unavailable counters explicit; none replaces the complete JMH comparison or establishes native input/FPS gains.
The copying neutral runtime preserves the original six-argument constructor and every shared semantic control; only the candidate's exclusive-array identity test is omitted from its source.
Frozen source/input bytes are supplied through the manual dispatch payload and declared source pins; transient inputs are absent from the execution tree.

Its optional `operationReport()` hook connects the same 60 method/parameter boundaries to the shared owner-thread meter.
Input samples retain the complete original opportunity cycle; initial-layout samples retain fresh host creation, attach, first frame and terminal close.
Scene setup, independent oracles, coordinate-read diagnostics and surviving-owner cleanup remain outside samples.
The six clean-frame rows are unchanged controls; small-line, signed-fallback and initial-layout costs remain visible in the complete comparison.
Supplemental reports preserve actual owner-thread CPU, owner/all-thread allocation and wall time with unavailable counters left unavailable.
The isolated lookup reports retain one untimed coordinate diagnostic after sampling; a baseline without that diagnostic records unavailable reads rather than an inferred zero.

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

The independent `-Pstrata.performance.benchmarks=NonpaintingRasterBenchmark -Pstrata.performance.suite=nonpainting-raster` corpus fixes 36 scenarios and two output-ownership methods, for 72 AverageTime cases.
It covers all four portable primitives, offscreen destinations, empty integer clips, nested fractional clips containing no physical centers, subpixel sampled destinations and exact zero-alpha sampled tint.
Empty and mixed lists, subsequent opaque or ordered translucent fills, densities one through four, original-coordinate regions, preexisting patterned destinations and visible/one-pixel controls remain separate cases.
Nine skinny physical extents test immediately below, at and above the 4,096, 262,144 and 1,048,576-pixel palette/full-fill admissions.
`verifyWork()` and trial setup compare complete pixels with an independent ordered scalar reference and verify immutable source ownership and the borrowed sentinel tail before collection.

`immutable` includes fresh result storage and image ownership without a returned-pixel copy; `borrowed` reuses one caller-owned array and includes required prefix initialization.
Invocation setup restores dirty borrowed storage without allocation outside the declared raster operation, warming that prefix; disclose this condition rather than interpreting ownership differences as one end-to-end presentation result.
Prepare and freeze one compiled fixture and collector for both runtime sides, preserve actual loaded archive receipts, collect three Standard repetitions per side and report every case and visible control with operation time and normalized allocation.

For a uniform output of N pixels, an invisible primitive before a full opaque fill conditionally removes one N-pixel intermediate materialization; final N-pixel initialization is still required.
At the 1,048,576-pixel single-translucent-fill admission, that invisible primitive also prevents entry to the 196,864-byte full-fill table path and its 65,536 initial alpha/channel combinations, leaving one scalar blend and final materialization.
The bounded palette path can restore uniform state and cause a later final materialization, so work accounting must include that pass rather than applying the large-table model to every extent.
Report these exact source-derived materialization, table and covered-pixel operations separately from measured JVM allocation and elapsed operation time; they are not measurements of compiler optimization or memory traffic.
Native upload, GPU completion and FPS are inapplicable to these isolated headless operations; any native fallback evidence requires its own actual collection.

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
The fixture borrows the formal `minimized` inactivity setting and observes the actual selected and applied limiter at the initial sampling boundary and every completed frame.
The configured limit is not evidence of the effective limit; modern menu or extracted render-state policies remain explicit in the receipt and must stay stable during each formal interval.
`-Pstrata.performance.inactivity=afk` selects a separate diagnostic condition rather than changing the formal default.
The paced fixture uses a distinct workload identity, and success is published only after borrowed options and native resources have been restored.
An explicit backend request must match the loaded device before sampling; an OpenGL fallback cannot certify Vulkan performance.
Legacy GLFW performance windows temporarily remove decorations so a full-height framebuffer fits the desktop; the previous decoration state is restored independently of viewport cleanup.
The 108-phase corpus measures settled presentation; it does not measure input, mutation, resize, release latency or GPU completion.
Terminal native resource release is a correctness assertion outside timing.
The ordinary Canvas acceptance path remains unchanged when the property is absent.
Each invocation keeps a separate client directory beneath its integration project's `build/run/native-performance/`, preserving the actual processed-mod code sources needed for later archive/class-tree verification and satisfying the existing client-run containment contract.
Do not delete these client directories before processing or replace their origins with standalone Maven files.
For legacy production clients, window validation resolves intermediary client/window owners and descriptors through the actual Fabric mapping resolver; development-only class names are not assumed in a remapped client.
Missing mappings, missing host members, dead handles and iconified windows reject collection rather than producing a valid zero-cost interval.

Process the three reports with `:quality:component-benchmarks:processNativeComponentEvidence -Pstrata.performance.request=<UTF-8-JSON-request>`.
The request supplies `collector` (the processor's actual loaded testkit JAR), `runs` (three `report.json` paths), a new `output`, and `cpu_report` (an actual JVM report or current JMH `receipt.json` with the same runtime binaries).
For selected collection, supply the same comma-separated IDs as `workloads`; sampled-image collection also requires `sampled_images: true`.
Selected standard evidence keeps the default warm-up, sample counts and three independent invocations, but certifies only its declared workloads rather than full-suite acceptance.
That JVM report or receipt supplies loaded archive/class-tree provenance only; its measurements are neither synthesized nor compared with native latency.
The adapter reads exactly one legacy `strata` or current `runtime_metadata` inventory without rewriting the raw receipt, requires a successful invocation and current per-iteration fork verification, and preserves strict file/resource/class-tree checks.
The adapter selects the five shared API/core/headless/Minecraft/font representatives from its real metadata, while Fabric remains native-only.
The shared kit validates collectors, independent invocations, registered conditions, exact phase matrices and actual CPU/native archive/class-tree bytes, then aggregates declared metrics.
The adapter additionally verifies the fixture archive, preserved PNG bytes, exact complete-frame counts and balanced native release.
Only invocation-specific output/terminal-receipt arguments are excluded from controlled JVM arguments.

For a baseline/candidate comparison, use the same processing task with `command: "comparison"`, `backend: "opengl"` or `"vulkan"`, and nested `baseline` and `candidate` objects that each contain three `runs` and their own `cpu_report`.
The unchanged top-level `collector`, `output`, `workloads` and `sampled_images` fields still apply.
Request file paths resolve from the repository root.
Each raw invocation remains bound to its side's actual CPU origins; duplicate invocation IDs, driver fallback, mixed fixture epochs and unequal controlled conditions reject comparison.
`JvmPerformanceReports.compare` owns all aggregation and metric comparison, and `compareImages` verifies the complete PNG inventory for each paired repetition.
Preserve raw reports, PNGs and the actual loaded archive paths until processing completes; copying a receipt does not change its recorded origins.

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
The compiled RenderPearl fixture records two timestamp pairs and uses the actual device's timestamp period to convert ticks to nanoseconds.
The GUI-only pair surrounds the real native GUI consumer and excludes uploads and sampled-target passes recorded before GUI consumption.
The full presentation pair starts before owner-thread frame preparation and ends after GUI consumption, covering intervening source/metadata uploads and ordered offscreen composition.
Its GPU duration does not measure CPU preparation time; the timestamps bound commands on the native device timeline.
`GpuPerformanceMeter` requires every requested pair to complete before publishing separate p50, p95 and p99 GPU distributions.
The native processor requires both complete scopes when GUI queries are available and rejects using the GUI-only scope as full presentation evidence.
Older unsupported adapters may omit the full-scope ancestor while their GUI queries are explicitly unavailable; the adapter registers no missing-path projection and preserves full GPU metrics as null with a reason.
Mixed ancestor presence or GUI/full-scope measurement availability across one complete raw matrix is rejected, and available scopes must retain complete duration and completion-observation sample counts.
A separate owner-operation-to-first-observed-GUI-completion distribution includes CPU work, queueing and polling delay and supplies an upper bound at that host observation cadence.
Neither measurement certifies swapchain presentation, input-to-display latency or FPS.
Queries and their callbacks are owned by the fixture, bounded by the sample count, and completed and released outside measurement.
Other compiled families and disabled query collection explicitly report unavailable GPU measurements.
Compare repetitions only with matching version, backend, driver/device description, viewport, pacing, query mode and archive identities; different devices or backends remain separate evidence, not an interchangeable speed ratio.

`ColdImageBenchmark` is a separate six-case JVM corpus selected with `strata.performance.benchmarks=ColdImageBenchmark` on `:quality:benchmarks:jmhHistorical`.
It measures opening a fresh input stream, PNG decoding and immutable pixel acquisition separately from deterministic PNG encoding at 64, 256 and 1024 texels per axis.
The encoded input is resident before measurement, and PNG encoding returns fresh bytes without filesystem persistence; these boundaries do not claim an operating-system file-cache or network-cold workload.
The historical, sampled-raster and native steady-presentation definitions and defaults remain unchanged.

Select `strata.performance.benchmarks=ColdImageBenchmark,PngEncodingBenchmark` and `strata.performance.workloads=ColdImageBenchmark.encodePng,PngEncodingBenchmark.encodePng` on the same task for a 15-case encoding corpus: the three existing cold-image resolutions plus full-HD and stored-block boundary dimensions, each with transparent, opaque and varying partial alpha.
The boundary cases contain 65,534, 65,535 and 65,537 filter-zero RGBA bytes; no positive RGBA image can produce exactly 65,536 scanline bytes.
Source construction and independent image decoding remain outside timing, and every operation returns fresh output storage.
This selection preserves the six cold-image cases and every historical default; it uses the same standard JMH settings, controlled runtime selection and shared-kit provenance.
The optional `PngEncodingBenchmark.verifyWork` hook checks all twelve generated supplemental cases against an independent canonical byte encoder before timing.

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
The Web runner accepts an optional fifth performance argument containing a JSON map of additional frozen input names to file paths, resolved relative to that manifest; it records and rechecks the manifest and inputs through the same shared driver.
Untimed fixture registrations run in the ordinary complete Web verification path; its optional third argument selects baseline or candidate expectations and defaults to candidate.
The 36 Button controls restore native Canvas descriptors after each control, retain Text's measurement/failure path and exercise independent normal-property/pixel parity and terminal release; comparative evidence also supplies the actual compiled integration fixture artifact and control inventory/source guards in the input manifest.
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
For a frozen runtime pair with different binary surfaces, register complete per-module TSV inventories through the existing `strata.performance.fixtureInputs` manifest using `runtime-api-variant.<module>.<full-archive-sha256>` labels.
Both sides use the same input union; verification selects each module's complete inventory by the hash of its actual loaded archive and checks every member against that inventory.
Supply all four registered modules, at most two archives per module, and nonempty unique-member tables bounded to 4 MiB and 16,384 rows each.
Malformed bindings, missing actual archive matches, duplicate members and cross-module assignments fail before sampling; ordinary checks without variant inputs retain the checked-in registry.
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

The supplemental `TextLineLayoutBenchmark` corpus selects the actual retained TextArea and multiline Text public entry points with `-Pstrata.performance.benchmarks=TextLineLayoutBenchmark -Pstrata.performance.suite=text-line-layout` on `:quality:component-benchmarks:jmhComponents`.
Its 82 generated cases separate 60 keyed initial construction, content-edit and width-reflow opportunities from 22 clean, restoring caret and complete preedit/focused-range/loss controls.
Initial construction replaces a retained component within an already primed host/font lifetime; it does not include profile/backend creation.
Each CPU and allocation interval includes the complete public mutation/input/frame cycle, with no per-invocation setup, reflection or oracle work.
Inputs cover empty, short, 128 and 32,767 BMP scalars, 32,767 supplementary scalars with sufficient explicit UTF-16 state capacity, character wrapping, CRLF/hard breaks, signed/zero/exceptional advances, font spans and first/middle/last ellipsis cuts.
TextArea font changes select a whole editor font; multiline display text also supplies mixed spans.
The deterministic custom ordering probe preserves original logical caret metrics and does not establish native bidi or IME acceptance.
The generated fixture gate verifies independent original boundaries and forward Float-prefix/native rounding, compact visible arrays, nearest/caret/range lookups, semantics, bounded synthetic reference pixels at densities one through three, immutable old lines/frames and zero terminal faces/backends.
Raster checks cover a 64-by-64 logical window; long-line suffix admission is checked by complete scalar/run oracles, not by a full-width image claim.
Both runtime sides must load the same compiled fixture and helper/generated classes, collector, harness and reviewed API inputs, with only the target Minecraft runtime archive varied.
Freeze a reviewed neutral factory baseline retaining the original copies before measuring the owned-array candidate so both sides share the exact internal host inventory registration.
Prepare with fully qualified JVM tasks and the generic `-Pstrata.jvmOnly=true` model; quote dotted properties in PowerShell and keep final aggregate/platform verification unscoped.
Collect three independent Standard repetitions per side and retain every case, control and regression through the existing shared processor.
This native-free JVM fixture measures layout opportunity CPU/allocation, without GPU uploads, native input latency or FPS evidence.

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

`IncomingFragmentBenchmark` is a separate selected corpus on `jmhRemote`, with 120 generated cases and unchanged Standard JMH warm-up, measurement, fork and profiler settings.
Select all three methods with `-Pstrata.performance.benchmarks=IncomingFragmentBenchmark`; the original 30-case protocol corpus remains unchanged.
Its 1/8-owner matrix retains minimum, small and maximum fragments, 1 MiB and near-message-limit transfers, reversed and gap-filling input, duplicate/stale/incarnation/discovery controls and 64-byte negotiated fragments.
`admissionCycle` measures a complete invocation lifecycle around actual decoding and bounded reorder admission; `assemblyCycle` additionally includes defensive native inbox snapshots before ordered drain and logical assembly.
Both retain the public decode/Frame-offer control beside the loaded production route.
`serverIngressCycle` separately invokes the actual common-service native inbox and bounded receive phase, with fresh discovered peers and literal native input.
It does not substitute a copied server loop or use the public control to represent the production server.
Literal source construction is outside JMH timing, while every JMH cycle includes fresh service/owner/queue preparation, discovery or stale preparation, the selected core operation, downstream complete output checks and terminal release.
This keeps elapsed and GC-profiler allocation on the same declared lifecycle scope and avoids invocation-level timestamp distortion for tiny controls.
The supplemental shared CPU meter isolates Admission, Assembly and ServerIngress with preparation, downstream parity and release outside each interval; retain and compare these separate scopes independently.
The identical reflection adapter and logical-owner entry remain inside the selected operation on both runtime revisions.
Native adapters retain their loaded-client/host acceptance obligations; this corpus cannot establish native scheduling or complete screen latency.

`IncomingFragmentCpuEvidence` uses the same frozen fixture with the shared owner meter, 100 warm-up invocations and 200 measured invocations, and records the full 120-row matrix without consumer sampling code.
Fresh queue/owner preparation, actual-array identity/copy probes, parity and cleanup are outside each measured sample.
Its work counts report necessary decode storage, admitted fragment storage, actual queue-copy bytes/arrays and downstream assembly separately from normalized allocation.
The opaque admission handle and owner capture are still measured overhead, so tiny packets and rejected-frame controls can regress even when queue-copy work disappears.
These work counts do not claim every source allocation survives JIT optimization.
`IncomingFragmentCpuComparison` consumes three independent baseline reports, three candidate reports, the frozen collector JAR and a fresh destination through `JvmPerformanceReports`.
It retains all controls/regressions, runtime and compiler-inventory provenance, and unavailable metrics as null; native uploads/GPU/FPS are N/A.

For an archived-runtime comparison, `strata.performance.remoteInventory` selects an explicitly preserved exact compiler inventory declared in `strata.performance.fixtureInputs`.
Keep both baseline and candidate inventory files in the identical immutable input map on both sides; selecting one never changes the runtime classpath or relaxes the exact loaded-surface comparison.
Ordinary `check` and collection without this explicit archived input remain bound to the current checked-in `remote-api.tsv` registration.
Capture each inventory from its actual compiled archive with `captureRemoteInventory`; review permitted opt-in additions rather than inventing JVM symbols or accepting a package wildcard.
The selected inventory is admission metadata and remains on the entry-point JVM, bound to its exact immutable input by `RemoteInventoryArguments`.
Timed forks receive every other inherited JVM argument unchanged through standard JMH options; explicit command-line or annotated fork replacements are refused rather than overwritten.
Raw JMH arguments, input inventories and comparison conditions remain exact, with no path normalization in the shared comparator.
Freeze one compiled fixture and shared collector after source/work preflight, and preserve exact source, archive and input hashes for three independent Standard invocations per side and per measured mode.
The incoming comparison baseline must already include the bounded-drain change on both sides so its saving is not counted again.

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

## Opt-in paired JVM CI

The `JVM checks` workflow accepts complete frozen comparison metadata through `workflow_dispatch` inputs on the existing product branch.
Prepare the manifest and any patches under `.github/performance/` in a local staging directory outside the repository; do not commit that directory or create measurement branches.
Encode it with `python -B gradle/performance/paired-jvm.py --pack-inputs <staging-directory> --manifest .github/performance/<campaign>/manifest.json --output <new-request.json>`.
Pass the resulting input object to `gh workflow run jvm.yml --ref <product-branch> --json` through standard input.
The `paired_manifest` input names the manifest inside the metadata ZIP; `paired_inputs` contains its base64-encoded XZ stream.
Compression covers the complete ZIP so repeated baseline/candidate inventories share the same compression window.
The runner reads the immutable event file directly, including on Windows, rather than putting the payload in shell code or an environment variable.
The payload admits at most 45,000 compressed bytes, 256 regular metadata files and 4 MiB expanded; oversized inputs fail before dispatch packaging completes.
Source pins outside the metadata subtree resolve against the selected driver checkout.
Large generated inputs may remain in a previous same-repository Actions artifact instead of the dispatch payload.
An optional local `.github/performance/dispatch-artifacts.json` list binds each provider's `id`, `bytes`, `sha256`, inner ZIP `archive` and `files` mappings from `member` to `target`.
Each target must be an existing manifest input pin under the metadata subtree; packing omits its bytes and execution restores only byte-identical declared inputs.
Providers are limited to four 128 MiB archives, expansion to 4 MiB per provider, and XZ decoding to 64 MiB memory and 6 MiB output.
The runner uses the job's read-only Actions token and keeps hash-checked downloads only in that job's temporary directory.
An expired or changed provider fails before Java starts; no Git input branch is created as a fallback.

Planning and execution independently verify the ZIP digest, manifest digest, exact source trees and every input pin before Java starts.
The reconstructed candidate must match every non-data path, mode and blob of the actual driver revision.
The existing `paired_data_ref` output and `input_producer` receipt contain the ZIP SHA-256 for dispatch inputs.
Planning uploads the exact ZIP as a run artifact, and the complete attempt retains another byte-identical copy.
Each paired dispatch has its own concurrency group, so comparisons on the same product branch do not cancel each other or ordinary PR verification.
Manual full CI with empty paired inputs and ordinary PR verification keep their existing selection.
Historical producer/consumer trailers remain readable by the CLI for replay, but new workflow dispatches require the direct payload and never create those Git references.
A failed attempt is retained in full and cannot be resumed by adopting successful rows from a previous attempt.

Start another comparison from [the manifest template](../../gradle/performance/paired-jvm.example.json).
The schema requires these fields:

| Field | Contract |
| --- | --- |
| `sources` | Exact commit and final tree SHAs for `fixture`, `baseline` and `candidate`; optional binary patches have repository-relative paths and SHA-256 pins. |
| `runtime_projects` | The actual JVM project archives replaced in both the work gate and collection classpath. |
| `suites` | Existing collector project, independent output name, generated benchmark/method selectors, compiled parameter values and complete expected case count. |
| `inputs` | Unique input labels bound to immutable repository-relative file pins and passed to the existing provenance collector. |
| `compiled_pins` | Optional predeclared fixture and runtime artifact pins for each source binding; a rebuild mismatch rejects the attempt. |
| `dependency_pins` | Optional frozen control JAR pins relative to the actual Gradle user home. |
| `java_pins` | Optional fixed Windows `java.exe` and `release` pins for the `toolchain` and `launcher` roles. |

A suite may additionally declare `profilers` as unique compiled JMH profiler class names.
The driver passes these through `strata.performance.profilers` to the standard JMH `-prof` option only during collection; the existing GC and provenance profilers remain enabled.
No fixture class name is registered in the build script or driver.
Custom secondary metrics remain in the complete raw JMH results and the shared processor reports their independent-run medians, units and baseline/candidate deltas alongside its standard latency/allocation/GC metrics.
Additional metrics require complete per-fork/per-iteration scalar data, the same metric inventory in every repetition and both sides, and unchanged units.
An owner requiring an additional CPU metric must verify its actual availability across the whole selected matrix before accepting that corpus.
Such a corpus freezes the collector containing this generic secondary-metric processing; an earlier frozen collector is not silently replaced or certified as supporting the new output.
Input labels may contain ordinary filename dots, so existing labels such as `unihex-bounds-assets.zip` remain unchanged; label values are never interpreted as filesystem paths.

A suite may declare optional `test_gates` for independent tests on both original reconstructed runtime sources before any timed collection.
Each gate has an existing runtime `task` and a nonempty `required_tests` inventory of exact `class#method` bindings; filters and additional task arguments are not allowed.
The driver runs the complete task on baseline and candidate without the fixture overlay, preserves all actual JUnit XML even on failure, and checks report counts and successful execution of each required method exactly once.
These independent tests supplement the fixture's complete work and loaded-runtime controls.

A suite may also declare an optional `preflight` object for a qualified compiled untimed controller.
It has exactly four fields: `main` is a qualified class name already compiled in that suite's fixture classpath; `qualification` is an immutable repository-relative file pin containing the owner's independent controller qualification evidence; `runtime_classes` maps every declared runtime project to its loaded representative class; and `controls` is the complete ordered inventory of unique `id` and `arguments` records.
Arguments are data passed in a pinned JSON request, never shell commands or additional JVM options.
The same control inventory and qualification bytes apply to both runtime sides.
Controller qualification remains an independent owner responsibility: a pinned record and a generic work gate cannot certify a particular tracer, native path or lifetime control.

The controller's static `main(String[])` receives one absolute request-file path.
The `strata-paired-jvm-preflight-request-v1` request records the suite, side, full control inventory, preserved qualification, runtime/classpath byte pins, required loaded classes, source archives, actual Java identities, frozen inputs and a fresh output directory.
The controller executes every requested control before returning and writes `receipt.json` with schema `strata-paired-jvm-preflight-receipt-v1`, `status: passed`, the actual request's `request_sha256`, and its complete ordered `controls` array.
Each control contains its `id`, `status: passed`, nonempty structured `observations`, `origins` for every required class as `{ "pid": actualJvmPid }`, and nonempty `artifacts` containing repository-style relative `path` and actual `sha256` pins inside that control's output directory.
Separate observation files retain actual traces, counters and independent proof rather than replacing them with a success boolean.
Missing, repeated, reordered, failed or unavailable controls, changed request/qualification/input/classpath bytes, unsafe observation paths and mismatched loaded origins fail the attempt.

The shared init script creates the generic controller JavaExec from the existing suite work gate's dependency graph, launcher, native-access options and classpath.
Its measured-fork count must be zero.
An external controller launching its own untimed JVM must keep the inherited diagnostic/resource profile, emit its actual `paired.jvm.origin` record, and provide naturally released owning Job generations and PID-qualified class-load origins for those observations.
An independent controller may retain a complete class-load file as a pinned control artifact.
The reader binds that artifact to the exact file flag in its immutable VM diagnostic and selects the corresponding captured PID/birth generation, including legitimate PID reuse.
For relative flags, the correspondence to a fresh absolute artifact follows the verified executed controller's path construction; the reader records that relationship as an inference and leaves the working-directory observation unavailable.
Missing, changed, duplicate, wrong-PID or ambiguous file/generation attribution fails, and console output cannot supply another generation's origin.
Tracing belongs to this separate untimed route; the driver supplies no instrumentation option to timed JVMs and retains the same immutable fixture/runtime/collector/harness closure for collection.
Every selected suite's full work gates and both sides' independent controls complete before the first timed collection in the attempt.

Supported collector projects are `:quality:benchmarks`, `:quality:component-benchmarks` and `:quality:remote-benchmarks`.
They use the existing `jmhHistorical`, `jmhComponents` and `jmhRemote` tasks and full `verifyHistoricalWorkloads`, `verifyComponentRenderingWork` and `verifyRemoteWorkloads` work gates.
Generated fixture selection does not require a central benchmark registry or a fixture-specific build-script branch.
Remote protocol and retained-session defaults remain separate existing scopes; a supplemental compiled remote fixture uses the same generated selection contract.
Select CPU fixtures whose required toolchains fit the fixed catalog profile.
The existing component task also supplies OS-classified LWJGL/STB/FreeType native JARs, fixed font inputs and native access for CPU font-library controls.
These controls do not require a graphics window or loaded game, but their actual DLL load, workload parity and release must pass in the job; a missing native library or required control fails the whole corpus.
GPU/window/upload/input-latency/FPS and loaded-game acceptance is N/A in this CPU route; real game, browser and Paper/Velocity host evidence uses its own integration route.
This manifest does not bind versioned Fabric adapter archives, Web/browser runs or Velocity main/probe and separately registered remote inventories.
It also does not reproduce an unrestricted toolchain-discovery control or change its original RAM/metaspace conditions.
Such owners retain their existing complete evidence routes; JVM success cannot substitute for those domains, and a changed measurement environment cannot be presented as the same comparison.

Each source is reconstructed in a private checkout with `core.autocrlf=false`, staged patches and an exact `git write-tree` guard.
Private checkout attributes disable text conversion so working files preserve the frozen Git blob bytes, including older nonnormalized CRLF blobs.
Both those attributes and the complete working tree remain guarded between invocations.
The common compiled fixture and collector remain unchanged while baseline and candidate runtime archives are rebound explicitly.
No old workstation owner directory, private index or missing historical metadata file is a prerequisite.
Fixed byte pins cannot be replaced by newly generated hashes after a mismatch.

All selected suites' full work gates, optional independent runtime tests and optional independent preflight controls run before sampling.
For each suite, the driver collects AverageTime and SampleTime separately in `B0/C0/B1/C1/B2/C2` order, with three warm-up iterations of one second, five measured iterations of one second, one fork, one thread and the existing GC profiler.
Quick, smoke, repetition and timing overrides are not manifest fields.
The existing test kit validates per-fork and per-iteration provenance and owns collection, percentile processing, median aggregation and comparisons.
The report displays the processor's baseline/candidate medians, absolute deltas, allocation and GC metrics, and SampleTime percentile medians.
Time is per declared fixture batch; it has no cross-machine improvement interpretation or wall-time pass threshold.
JMH Thread CPU per operation is unavailable unless a separately declared profiler supplies it; Job CPU includes preparation and descendants.
Supplemental owner-thread operation reports have their own explicit boundary and protocol described below.

The optional `owner_reports[].paired_jmh` connection preserves an existing four-argument collector whose final arguments are its original JMH receipt and prepared source plan.
It declares one complete suite and its compiled fixture class, binds both actual prepared source trees and ordinary runtime JARs after preparation, and creates that immutable source plan once before collection.
Owner invocations reuse the original JMH input-property origin and complete selectors through the existing JavaExec template; the collector, sampler, comparison processor and all VM flags remain unchanged.
Producer payload pins are read through the immutable input root, while execution-head source and live driver guards remain separate.

### Supplemental operation reports

A suite can opt into `operation_reports` without changing its JMH selectors, modes, forks, iterations or deterministic work gates.
The existing three supported fixture projects use their existing collector tasks and classpaths; no fixture-specific Gradle task or launcher is required.
Omitting this field never discovers or invokes an adapter, and the fixed pilot remains unchanged.
The field requires an `adapters` object mapping each selected fixture's binary class name to its compiled adapter class name, `warmup`, `samples`, `expected_rows` and `expected_controls`.
The complete operation row count must equal the original selected JMH method/parameter count for one mode; each row also binds both original AverageTime and SampleTime inventories.
The six supplemental invocations and their cell counts are distinct from the twelve original JMH invocations.

```json
"operation_reports": {
  "adapters": {
    "dev.s7a.strata.quality.benchmark.CycleSelectionBenchmark": "dev.s7a.strata.quality.benchmark.CycleSelectionOperationReport"
  },
  "warmup": 30,
  "samples": 60,
  "expected_rows": 48,
  "expected_controls": 30
}
```

The fixture declares a public static no-argument `operationReport()` returning `JvmOperationReportAdapter`; Kotlin companions expose it with `@JvmStatic`.
`collectVerified` runs the fixture's original complete work hook before collection by default.
An adapter whose original hook permanently closes its owner may override this boundary to verify and sample inside the same original owner lifetime.
That override must preserve all original controls and terminal release; it cannot reset the owner or omit verification.
The adapter declares `JvmOperationReportRow` identities, complete method/parameter bindings, boundaries, control identities and explicit operations per sample.
Missing required, duplicate, ambiguous, invalid or stale hooks fail before collection can publish valid evidence.
Every selected JMH method and parameter row must bind exactly once; the adapter cannot silently drop independent controls.
The shipped [selection adapter](../../quality/component-benchmarks/src/jmh/kotlin/dev/s7a/strata/quality/benchmark/CycleSelectionOperationReport.kt) preserves all 39 fixtures, 48 complete operation rows and 30 controls.
The independent [composition adapter](../../quality/benchmarks/src/jmh/kotlin/dev/s7a/strata/quality/benchmark/NonuniformOverlayOperationReport.kt) binds every row of its existing six-case corpus.

Adapters use `JvmPerformanceRunner.warmup` and `sequence` rather than implementing a sampling loop, clock or counter.
Prepare and prime fixtures before `sequence.sample`, execute every required complete-operation step inside its callback, and keep assertions, report serialization and ordinary surviving-resource teardown after it.
Lifecycle rows retain required terminal close inside the actual operation; setup allocation is never subtracted from stock JMH GC allocation.
Retained main fixtures and fresh invocation controls keep their own original ownership and preparation rules.
Failed operations, incomplete sample counts or terminal cleanup failures cannot publish a successful report.

Supplemental invocations run serially in `B0/C0/B1/C1/B2/C2` order on the same qualified host, outside JMH timing, with the same frozen fixture/collector/adapter and external inputs against each runtime.
Each raw `report.json` binds its independent UUID to the corresponding complete original AverageTime invocation and preserved receipt digest.
Loaded fixture, adapter, collector, runtime class trees, actual JVM/host properties and input identities are checked around collection; source/tree/archive bindings remain in every report and the whole attempt archive.
Adapters declare actual consumed resource and external file paths through `inputs`; every original JMH input must match a current source file, including resolved control libraries.
The collector re-reads mappings and bytes after collection, and processing revalidates both those actual files and the semantic input-manifest mappings alongside the original JMH archives.
Missing, unknown, redirected or changed inputs fail instead of reusing an old archive identity.
Processing uses `JvmPerformanceReports.compare`, `PerformanceReportContract` and registered `PerformanceReportMetric` projections.
A changed adapter or collector requires a new paired freeze and fresh evidence on both sides.

Owner-thread CPU is `owner_thread_cpu_ns / samples` in ns/sample; owner-thread allocation is `owner_thread_allocated_bytes / samples` in B/sample.
A sample is the declared complete operation or explicit batch; raw totals, exact denominators, operations per sample and original distributions remain preserved.
CPU/allocation percentiles keep their own units; the existing allocation distribution's legacy `_ns` field suffix denotes the collector's stored numeric distribution rather than converting allocated bytes to time.
The comparison preserves independent per-run values and medians, absolute deltas and nullable unavailable fields.
Unsupported CPU or allocation counters remain JSON null through collection, comparison and rendered tables, never a fabricated zero.
Wall time, process/all-thread counters, job CPU, heap-boundary samples and stock JMH GC allocation retain their separate scopes.
Heap-boundary changes do not establish retained-object size, and these counters do not certify browser, GPU, native upload or loaded-game costs.
Existing meter reads and adapter dispatch have collection cost; cost comparisons require actual matching fixture measurements and remain Pending until collected.

Every supplemental preparation, collection, processing and cleanup phase uses the same fresh RAM admission, fixed JVM caps, worker and affinity controls, owned Windows Job, deadlines, no-breakaway and terminal resource checks.
A failed attempt cannot donate rows to a later attempt; an unsupported whole workload is reported as a failure without substituting a subset.
Full original JMH and applicable native/runtime Issue acceptance remain independently required.

Every Gradle invocation requires fresh available physical RAM of at least 4096 MiB, checked again inside its owning Windows Job immediately before Java.
The suspended caller is assigned before it can start Gradle; descendants inherit a 3 GiB committed-memory limit, kill-on-close and no breakaway.
One invocation has 1800 seconds of work and 30 seconds of cleanup, one Gradle worker and one test fork.
The hosted job has a six-hour bound, with a reserve for terminal evidence and upload; these limits are fixed rather than exposed as manifest overrides.
An entire suite and all twelve collections must fit those bounds.
A selection whose required full iterations already exceed the invocation or attempt bound is unsupported; do not shorten its iterations, drop controls, select fewer cases or combine partial runs to make it fit.
This single-runner route does not shard a corpus across distributed executors.
The finite heap/metaspace profiles are wrapper 64/256 MiB, daemon 768/1024 MiB, probe 32/256 MiB, compiler/generator/test worker 512/256 MiB and JavaExec/JMH fork 768/384 MiB.
The inherited Java environment supplies the probe heap cap, while each ordinary launcher retains its explicit profile.
JMH compiler-blackhole `-version` probes have no main or classpath in HotSpot diagnostics; only their exact inherited arguments and known compiler-hint options are admitted, with a pinned Java home and a validated JavaExec that loaded JMH compiler hints.
These probes remain separate diagnostic records and never count as measured forks.
The boundary queries at most 256 live members and retains only still-active owned process handles.
An incomplete Job list is never admitted; membership changes trigger another complete bounded query at the original 100 ms polling cadence within the same phase deadline.
First and last incomplete query headers remain recorded separately from genuine capacity refusal.
The existing 4 MiB terminal-reader capacity bounds history before insertion: each generation charges 1024 bytes for fixed fields/PID/list formatting plus six bytes per UTF-16 image character for worst JSON escaping.
Accepted history has a 3 MiB serialized upper bound; the first refused generation is preserved before failure, with at most 197,632 additional bytes from the existing 32,768-character image buffer.
The remaining report capacity covers fixed terminal metadata without truncating a generation or increasing the reader limit.
Each signalled owned handle records its actual exit and PID/creation-time identity before closing; immutable generation lists preserve legitimate PID reuse instead of replacing history.
VM checks select exactly one matching birth and reject missing, ambiguous, altered, active or wrong-affinity generations.
The real boundary controls include 288 completed observed descendants with eight concurrent children, alongside the unchanged normal, failure, RAM-refusal, timeout and leaked-child cases.
A separate eight-concurrent-child control reaches the actual history budget under the same source-control deadline and memory profile, retains the first refused generation and proves bounded-report failure plus zero owned cleanup.
Closed JVM diagnostics verify actual effective caps and Java origins; measured JMH forks additionally require captured OS generations, zero exits, membership in the actual Job and unchanged inherited affinity.
Full class-load attribution uses one fixed attempt-owned staging directory so the logger argument remains identical across measured repetitions.
Separate per-VM files have no rotation and a 1 MiB limit per file; immutable copies retain each phase's newly created logs, including failed invocations.
Previously captured staging files must remain byte-identical before the next invocation.
The driver checks filename and content PIDs, complete line endings, captured file hashes and matching JVM records; reused PIDs require distinct raw diagnostics bound to the VM filename stamp or its actual header start time; missing or changed fork attribution fails instead of borrowing console output.
Start-stamp matching also applies when only one class-load file has that PID; a short version probe without its own class-load file cannot borrow a later fork generation.
Unified class logging expands `%t` from VM initialization time, while LogVMOutput expands it when opening its file, so their filename seconds can differ.
The actual HotSpot header start milliseconds, formatted to seconds in the same host's local clock, may select exactly one fresh pinned class file and captured PID/birth/lifetime generation even when that PID has other Java generations in the phase.
This exact selection still verifies the actual file flag, source owner, executable, caps, affinity and complete loaded origins; multiple filename/header candidates remain ambiguous and no time tolerance is introduced.
If neither exact filename nor header start selects a file, attribution may instead use exactly one phase VM record, one captured Java executable PID/birth/lifetime generation and one newly copied class file matching that VM's actual nonrotating file flag and immutable inventory pin.
This path verifies executable, effective caps, affinity, complete PID-qualified origins and previous staging-file immutability; any additional VM, Java executable generation or candidate file keeps attribution ambiguous.
Closed non-Java helper generations that reuse the PID remain separately recorded with their actual image, birth, exit, Job membership and affinity; the sole Java generation must match the VM's actual executable and lifetime.
Both standard Java launcher names, `java.exe` and `javaw.exe`, count as Java generations; this distinction does not admit a different main executable.
Relative file-flag correspondence is inferred from the verified bounded launcher's staging contract and is not an observed working directory or proof that the filename clocks match.
Legacy unstamped VM diagnostics remain readable only when that VM PID is unique within the complete phase.
Independent JDI observations retain their own pinned raw files and may corroborate one identical loaded origin; conflicting or duplicate origins fail.
The driver records actual host/OS/power information and observed affinity rather than requiring a workstation mask or a physical-core qualification.
An overflow of the retained 16 MiB invocation console bound, failed admission, nonzero exit, timeout, leaked descendant or incomplete receipt fails the whole attempt and stops the next invocation.

The artifact retains source archives, frozen manifest and its producer/driver binding, the exact shared driver sources, compiled common fixture closure, raw receipts/results, comparison requests/results, JVM diagnostics, per-phase requests and terminal records, host/executable hashes, phase durations and a file hash inventory.
Runtime JARs and immutable inputs are copied before the first work gate; a preflight suite additionally preserves every classpath archive/directory, including fixture, collector, harness and control tooling, before that gate's JVM starts.
Copies are checked against original bytes before and after preservation, independently guarded across control and collection phases, and kept outside reusable workspaces even when the first control fails.
Independent preflight requests, qualification records, actual observations, receipts and verified inventories remain in the failed or successful attempt archive.
`attempt.json` is authoritative about success or failure; caches and private build workspaces are excluded from acceptance evidence.
The workflow uploads failed attempts and checks failure, RAM-refusal, timeout and leaked-child cleanup through the same process boundary after collection.
Acceptance requires the whole selected case matrix and both comparisons, the process-boundary controls, and ordinary quality/ABI/Qodana and supported-version checks on the final driver revision.

Changes to the shared performance controller select the hosted `Qualified compiled fixture controls` qualification job.
It compiles an independent test controller into the existing generated remote fixture classpath without editing a collector build script, completes both modes' whole three-baseline/three-candidate pair, and records actual collection and control phase costs.
Fresh missing-control, altered-archive and controller-failure attempts must retain their early archives and actual terminal evidence while producing no timed output.
The same Job helper also proves actual RAM refusal, deadline and leaked-child cleanup.
This tooling qualification does not accept a consuming project's benchmark-specific tracing or native evidence; those retain their own complete frozen acceptance contracts.

An optional `owner_continuation` selects one complete independent `owner_reports` domain by its exact `name`; the outer manifest retains every original JMH suite, owner row and source/input binding.
It pins `runtime_sha256` for every original baseline/candidate runtime project and `collector_sha256`, and the driver rejects changed compiled bytes before collection.
The ordinary CI entry then prepares and collects all rows of that domain in three independent repetitions per side using the existing owner collector and processor.
The attempt explicitly records `complete-owner-domain-continuation`, `whole_issue_acceptance=false` and `prior_rows_adopted=false`; it cannot certify the whole issue or turn a prior failed attempt into a completed one.
Previously retained reports remain separately bound historical evidence and require their own source, archive, control and acceptance review.
The optional `--owner-report` entry selects an entire declared independent domain under the same scope rules and cannot override a configured continuation or select individual rows.
