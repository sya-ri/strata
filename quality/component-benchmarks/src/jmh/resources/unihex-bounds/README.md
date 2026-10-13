# Unihex natural-bounds comparison inputs

This complete corpus compares the actual runtime natural-bound helper and public consumers while changing only `FontHexGlyph.bounds`.
The historical fixed baseline was synced master `2e8088d8c9281b0a3bb34249fdd058cdd7231112`, including accepted complete-JMH Qodana tooling and the shared compiled-preflight paired JVM route.
Its runtime source is unchanged from the original baseline `414cb972ffa8c89a4aee2102d1dc2834dbe32eae`; both sides include the identical fixture and collector preparation.
Accepted prerequisites, if required, must be identical on both runtime sides and declared before collection.
Formal whole-corpus traces, all thirty acceptance controls and measurements remain Pending.
The external observer's actual compiled qualification is a producer-owned input referenced by the final HEAD commit's `Performance-Inputs` trailer; it verifies exact method and marker entries, bounded origin transport and zero-bounds class preparation.
It binds one, three and zero real bounds calls, one caught closed-engine failure and original TrueType, Bitmap and SnapshotLoad rows to actual loaded byte and owning-process evidence; it does not certify the complete corpus or a performance gain.

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
A top-start Stack preserves the complete viewport while admitting the Text natural size; the replacement control uses the same layout boundary.
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

The full paired manifest is a producer-owned input selected through the final commit's `Performance-Inputs` trailer and binds this complete corpus to the shared Windows CPU route.
Its immutable source patches reconstruct the same prepared fixture and collector on both runtime sides from the accepted master; only `FontHexGlyph.kt` differs.
The driver retains all 132 cases in each of three baseline and three candidate AverageTime collections (792 required cells), plus the separate complete SampleTime comparison (792 additional cells).
Input attributes preserve the original five resource blobs and the actual qualification bytes across checkouts.
Dependency and canonical Java byte pins come from the preserved known-control qualification.
Preparation preserves the actual fixture, collector and runtime bytes before admission; optional compiled predeclarations do not replace that evidence.
Actual source archive hashes, loaded origins, CPU availability, controls, collection costs and all measurements remain Pending until this corpus completes.
The shared CPU route supplements the existing external untimed tracing and real OpenGL/Vulkan, font ownership and supported-version acceptance; it does not replace them.
Register `assets.zip`, `fixtures.tsv`, `comparisons.tsv`, `measurements.tsv` and `controls.tsv` through the existing `strata.performance.fixtureInputs` properties manifest, with labels `unihex-bounds-<filename>` and exact absolute regular-file paths.
Preserve that same manifest's original files for both runtime sides.
Additional input labels never replace the collector's required library/native/TrueType controls.

Execute the existing `ComponentPerformanceEvidence` entry with the same packaged fixture/collector/harness and control classpath, substituting only the declared actual runtime archives.
Keep the shared archive/class-tree and normal parent/fork classloader certification enabled.
Use average-time mode, three one-second warmups, five one-second measurement iterations, one fork, one worker, GC profiling and the same `UnihexBoundsCpuProfiler` on both sides.
Pass `-Pstrata.performance.profilers=dev.s7a.strata.quality.benchmark.UnihexBoundsCpuProfiler` to the existing component task for every baseline and candidate invocation.
The CPU adapter uses the existing `JvmPerformanceMeter`; it owns no execution controller or sampling loop.
Its `unihex.processCpu` metric is fork process CPU nanoseconds divided by JMH's actual all-operation count, including iteration bookkeeping, JIT and GC.
JMH elapsed microseconds per operation and `gc.alloc.rate.norm` bytes per operation are separate metrics.
Do not label the JMH controller thread's CPU as workload CPU, replace unsupported values with zero or infer allocation savings from bit expressions.
See the pinned primary [JMH iteration metadata](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/IterationResultMetaData.java) and [GC normalization](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java) for the actual-operation denominator.

Before timing, the manifest's generic `test_gates` run the existing unfiltered `:runtime:minecraft:test`, `:runtime:minecraft-fonts-lwjgl:test` and `:runtime:minecraft-fonts-lwjgl:fontTestMinecraft263` tasks in each preserved baseline and candidate source workspace.
Every exact independent JUnit method declared by the manifest must run once and pass; missing, repeated, failed or skipped required methods reject admission.
The existing bounded Test profile disables output reuse and filters, and the driver preserves complete original JUnit XML even when the phase fails.
Controls 8–13, 20 and 28–30 retain their original scalar, ownership, failure and lifetime assertions; the private scalar helpers are exercised through their actual JUnit callers.
The CPU native-font tests additionally exercise real provider and PNG ownership against both runtimes.
Loaded OpenGL/Vulkan/native-font parity and all supported targets remain final acceptance requirements.
Both sides' independent tests, complete work gates and actual traces finish before any timed collection.

Before timing, run the packaged `UnihexBoundsMethodTrace` through the shared compiled preflight route for the complete R001–R132 inventory.
Each worker delegates to the unchanged `UnihexBoundsWorkEvidence` single-row entry on the actual preserved classpath.
JDK JDI observes the actual `FontHexGlyph.bounds()Lkotlin/ranges/IntRange;` entry and every selected cache-miss, raster and image-factory overload with breakpoints at code index zero of their loaded methods.
The controller installs exact entries while class preparation is suspended, handles already prepared classes, and rejects missing, ambiguous or non-executable entries.
The trace worker prepares the bounds class outside the operation so snapshot-only and non-Unihex controls also have an installed observer; neither frozen bounds class has a static initializer.
Both real marker descriptors use exact entry breakpoints, while actual return and exception notifications are enabled only between the begin and end markers on the operation thread.
Actual Unihex returns remain independently observed; setup and teardown stay outside all operation counters.
An actual null Unihex return is an absent result; a successful nonnull return records a constructed Unihex raster.
Public glyph calls, provider cache misses, bitmap cell misses, native raster requests and image-factory entries have separate counters.
Image-factory entries are not a count of completed rasters.
The marker adapter accepts actual String-reference arguments to both `PrintStream.println(String)` and `println(Object)`, suppresses only nested println delegation and retains the observed descriptor.
The operation thread owns each boundary, and a missing, repeated or incomplete boundary fails qualification instead of becoming a zero count.
First qualify the actual compiled observer with one, three and zero real bounds calls plus a caught closed-engine failure observed independently through a JDI exception event.
Also execute the original R127 TrueType, R128 Bitmap and R129 SnapshotLoad rows during qualification to prove their legitimate zero-bounds boundaries and actual return channels.
Bind that qualification to the actual compiled controller/worker hashes, preserved byte closure, PID-qualified loaded origins, finite VM caps and naturally released owning Job before admitting any trace or timed collection.
Startup class loads go to a retained PID-decorated file so a launching connector cannot block on an unavailable pipe.
After natural child release, each unique actual required-origin line joins the bounded shared console for the existing origin verifier; the complete unchanged class-load file remains a pinned artifact.
A missing or duplicate required origin fails the controller, and no console or artifact cap increases.
Keep trace runtime/fixture/classpath identities bound to the actual preserved archives used by collection.
Dirty Text's actual post-operation work diagnostics are retained separately from method counts.
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
