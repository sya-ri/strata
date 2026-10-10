# Unihex natural-bounds comparison inputs

This complete corpus compares the actual runtime natural-bound helper and public consumers while changing only `FontHexGlyph.bounds`.
The fixed baseline is synced master `414cb972ffa8c89a4aee2102d1dc2834dbe32eae`.
Accepted prerequisites, if required, must be identical on both runtime sides and declared before collection.
These sources are preparation; all execution, origin qualification, controls and measurements are Pending.

The committed [fixture table](fixtures.tsv) contains 36 immutable fixtures.
The [comparison table](comparisons.tsv) expands the geometry inputs into 120 rows and retains twelve additional complete consumer controls.
The [measurement matrix](measurements.tsv) contains exactly 792 Pending cells: 132 rows × (three independent Standard baseline collections + three independent Standard candidate collections).
The [independent controls](controls.tsv) preserve all thirty acceptance bindings; construction or source inspection never marks them passed.
The original detached ZIP/font documents and checkerboard PNG are stored in `assets.zip`.
The TrueType control additionally registers the repository's original redistributable font through the existing `cc0-geometric-font` collector input.

Each geometry input has exactly two records, U+0041 and U+0042, with identical sixteen-row bits.
Width 8, 16, 24 and 32 each have Empty, AllInk, LeftEdge, RightEdge, CenteredSparse and DisjointExtrema inputs.
The independent scalar reference retains the original unsigned Long shift, inclusive extrema and complete cropped ARGB array.
It never calls the changed bounds or ink implementation for expected output.
Unit tests additionally cover every single row/column, outside-width and signed bits, deterministic sparse combinations, noncodec widths, arbitrary owned row counts, defensive ownership, checked arithmetic and failure boundaries.

NaturalBounds invokes the real runtime class through one identical method handle on both sides.
PublicUncachedGlyph uses the public snapshot and engine, zero entry/byte cache bounds and provider preflight outside measurement.
Cold provider initialization remains inside EngineLifecycle.
CompleteDirtyTextFrame uses compiled source-backed Text, ContainerLabel styling, a 320×40 logical viewport and the ordinary default 4096-entry/16 MiB font cache.
Setup attaches and primes A/B, then each operation publishes the alternate literal and completes a retained frame.
These fixed dirty frames may have zero natural scans; their unchanged costs remain in the comparison.
Full independent ARGB, layout, pointer and semantics checks use physical densities one through three outside CPU timing.
Profile textures come from the unchanged compiled `ComponentProfile` factory; its bytes are part of the identical fixture archive.
Default font options, complete capabilities, provider/filter order and override documents stay fixed.
No source generation, archive creation, file reads or reference-pixel computation occurs inside a sampled glyph/frame operation.
Source loading, profile construction, attachment, use and close stay inside their explicitly named lifecycle/replacement control boundaries.

Use the existing generic generated-fixture discovery and `verifyWork()` admission.
Select `UnihexBoundsBenchmark,UnihexBoundsControlBenchmark` through the existing `strata.performance.benchmarks` property and use the shared collector and unchanged Standard settings from [the component task](../../../../build.gradle.kts).
Preparation/checks use the existing JVM-only model; complete acceptance uses the complete model.
No benchmark-specific Gradle task or property is added.
Register `assets.zip`, `fixtures.tsv`, `comparisons.tsv`, `measurements.tsv` and `controls.tsv` through the existing `strata.performance.fixtureInputs` properties manifest, with labels `unihex-bounds-<filename>` and exact absolute regular-file paths.
Preserve that same manifest's original files for both runtime sides.
Additional input labels never replace the collector's required library/native/TrueType controls.

Execute the existing `ComponentPerformanceEvidence` entry with the same packaged fixture/collector/harness and control classpath, substituting only the declared actual runtime archives.
Keep the shared archive/class-tree and normal parent/fork classloader certification enabled.
Use average-time mode, three one-second warmups, five one-second measurement iterations, one fork, one worker, GC profiling and the same `UnihexBoundsCpuProfiler` on both sides.
The CPU adapter uses the existing `JvmPerformanceMeter`; it owns no execution controller or sampling loop.
Its `unihex.processCpu` metric is fork process CPU nanoseconds divided by JMH's actual all-operation count, including iteration bookkeeping, JIT and GC.
JMH elapsed microseconds per operation and `gc.alloc.rate.norm` bytes per operation are separate metrics.
Do not label the JMH controller thread's CPU as workload CPU, replace unsupported values with zero or infer allocation savings from bit expressions.
See the pinned primary [JMH iteration metadata](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/IterationResultMetaData.java) and [GC normalization](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java) for the actual-operation denominator.

Before timing, use the packaged `UnihexBoundsWorkEvidence` single-row entry with R001–R132 for an external untimed JVM method trace.
Count actual `FontHexGlyph.bounds` entries and actual cache-miss constructor paths inside each printed operation begin/end boundary, excluding setup.
Distinguish an absent provider result from a constructed raster and distinguish raster-cache misses from public glyph requests.
Keep trace runtime/fixture/classpath identities bound to the actual preserved archives used by collection.
Dirty Text emits shared frame-work diagnostics through the same entry.
The source forecast is 16×width original ink predicates versus sixteen candidate row reads/OR combinations, one width mask and two bit-extrema operations per executed nonempty scan.
Those expressions are not total machine instructions or actual Text scan counts.
External method tracing is separate from timed CPU/allocation collection and never instruments the measured runtime archives.

Each side uses repetition indexes zero, one and two with fresh immutable output directories.
The shared JMH evidence processor validates raw result digests, loaded collector/harness/fixture/runtime origins, controlled settings and the complete 132-row matrix.
Preserve its validated elapsed/allocation comparison and the same raw receipt's process-CPU secondary metric.
Record natural scans, cache misses, constructed rasters, scalar expressions, row reads and complete frame work in separate columns.
Retain every unchanged or slower row and explicitly narrow or reject the optimization if whole-operation results do not support it.
Scalar-only results establish no native, GPU, upload or FPS gain.
Native timing, source/metadata/output upload bytes and offscreen-through-GUI/GUI-only GPU metrics are N/A unless an additional complete native comparison is separately qualified.

Ready requires current-revision JVM/quality/ABI tests, JVM-following Kover HTML/XML, Qodana, final-head scoped CI, all 22 supported Minecraft targets and applicable OpenGL/Vulkan/native-font parity with actual owner/lifetime controls.
Use `./gradlew check koverHtmlReport koverXmlReport -Pkover` at the required complete acceptance boundary.
Packaging and publication are unchanged; publication inspection becomes required if that scope changes.
Wait for CI through `gh run watch <run-id> --exit-status --interval 600`, preserving the original watch.
The full-source Draft may precede formal collection; Ready, merge and Issue closure remain separate gates.
