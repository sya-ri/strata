# Destination palette native acceptance

This is the remaining Issue 132 measurement plan, using the existing standalone `benchmarkMinecraft` fixture and `processNativeComponentEvidence` consumer.
All native performance values, compiled pins and final revision acceptance are Pending until actual collection and validation complete.
The two request JSON files beside this document are producer-only inputs to the existing processor, not launchers or collected receipts.
Resolve them from the pinned producer checkout; keep the processing source checkout and actual report paths separate.

## Common fixture and runtime binding

Freeze the final candidate source revision and its actual built testkit, native fixture and runtime archives before starting either side.
Use that same source revision for both build checkouts, restoring only `runtime/headless/src/main/kotlin/dev/s7a/strata/runtime/headless/SampledImageRasterizer.kt` on the baseline side from `7ebd9c94f3da521cadb76eadd4312289eaf88d7e`.
The baseline blob is `c96a54946b8fdd6c940d7ab4951243182db6842f`; the prepared candidate blob is `2e20f6bf2d6498467cf2a160a1e71bcdc782469f`.
Both source trees are reconstructed from accepted master `ecc3eef17e4d06a5eb9ef3e63a8639df541ae896` with the pinned binary patches in this producer namespace.
The source-only candidate tree is `772be0518cd1224755d08ba413b42e2a2d3313d8`; compilation and every current native receipt remain Pending.
Baseline omits the candidate-specific channel-admission test source; all six independent scalar pixel tests remain common, and all five candidate admission/retention/failure tests remain required on candidate.
Verify the actual path and blob before replacing it; both sides retain identical pacing, rollback, workloads, resources, compiler and collector sources.
Record the complete side diff, the common fixture archive SHA-256, the actual collector archive SHA-256, and actual representative resource and class-tree receipts.
A changed collector or fixture digest requires refreezing and recollecting both native sides.
Do not modify the historical 15-case JMH fixture or substitute native frame timings for its results.

Produce one real standard `:quality:component-benchmarks:jmhComponents` invocation per side on each processing host with `strata.performance.workloads=Canvas`, a fresh `strata.performance.output`, and the same five runtime archives that the native side will load.
This invocation supplies provenance only; its timings do not become native performance evidence.
Its successful `receipt.json` must retain `loaded-artifacts-per-iteration-v1` verification and these actual representatives:

| Runtime project | Representative |
| --- | --- |
| `:api` | `dev.s7a.strata.render.DrawImage` |
| `:runtime:core` | `dev.s7a.strata.runtime.UiSession` |
| `:runtime:headless` | `dev.s7a.strata.runtime.headless.HeadlessImage` |
| `:runtime:minecraft` | `dev.s7a.strata.runtime.minecraft.MinecraftUiHost` |
| `:runtime:minecraft-fonts-lwjgl` | `dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory` |

The preserved historical JMH receipts have a different three-representative inventory and cannot be converted into these CPU receipts.
Use the existing `gradle/performance/bounded-jvm.init.gradle.kts` runtime-archive substitution when needed, with an actual five-entry `strata.paired.runtime` properties file; the consumer still checks the loaded bytes independently.
Keep Fabric native-only and preserve its actual packaged origin.

## Exclusive collection

Use one admitted graphics host per backend comparison, with identical B/C host and driver conditions and no other build, browser or measurement workload during sampling.
The two backends may use separate qualified hosts; retain each host's actual archive origins and CPU receipts.
The repository currently exposes correctness jobs and the paired JVM workflow; it has no existing hosted B3/C3 native-performance launch workflow.
Do not start a native client until the coordinator grants the graphics session and the actual process/memory preflight has been recorded.
The canonical performance contract requires exclusive serial execution and preserves the existing heap; it declares no fixed 12 GiB minimum for this selected matrix.
Keep any separately authorized invocation guard tied to its actual workload and scope rather than inheriting an unrelated owner threshold.

Run each side three times per backend with the same standalone arguments, replacing only the fresh output directory and the requested backend:

```powershell
.\gradlew.bat benchmarkMinecraft '-Pstrata.minecraftVersions=26.3' '-Pstrata.performance.sampledImages=true' '-Pstrata.performance.workloads=SampledStationaryMedium,SampledTranslationMedium,SampledOrderedRowsSmall,SampledScrolledRowsSmall' '-Pstrata.performance.gpuQueries=true' '-Pstrata.performance.inactivity=minimized' '-Pstrata.canvas.scope=canvas-only' '-Pstrata.canvas.backend=opengl' '-Pstrata.performance.nativeOutput=<fresh-absolute-directory>'
```

For Vulkan, use `-Pstrata.canvas.backend=vulkan`; actual OpenGL fallback fails admission.
Use the existing Canvas-only performance entry identically on both sides to avoid unrelated font preparation; it preserves every selected case, GUI scale, capture and measured interval.
The native-output branch enters performance before ordinary Canvas oracles, so its terminal receipt establishes the actual selected performance lifecycle and shutdown only.
Collect independent Vulkan Float/Canvas correctness separately through the existing Canvas-only correctness task without `strata.performance.nativeOutput`; retain final all-target full-suite CI.
The standard fixture retains 30 warm-up frames, 60 complete sampled frames per phase, eight settle frames, 1920×1080, and GUI scales 1–4.
There are 16 phases per invocation and 12 independent processes across B3/C3 and the two backends.
Alternate baseline and candidate invocations without overlap, retain every actual UUID, and record launch/exit logs and native terminal receipts.
Do not use quick, smoke, AFK or correctness-only results as formal native timings.

Resolve all output locations against the candidate processing checkout before launching either side, so the raw reports and their recorded absolute image directories agree with the repository-relative requests.
Use `build/performance/issue132/cpu/<side>/` for CPU receipts and `build/performance/issue132/native/<backend>/<side>/run-<0|1|2>/` for native outputs.
Keep the baseline and candidate client directories and actual loaded archive origins intact until comparison finishes.

## Complete selected matrix

The controls exercise the direct native image path; ordered and scrolled rows exercise non-identity-tinted portable fallback.
Verify the actual fallback receipts contain positive portable rasterization and zero direct GPU tiles for the selected fallback scene.
These 32 selected rows cover this change's native impact; they do not claim the unrelated full component corpus or all rendering-family performance matrices.

| Backend | Case | GUI scale | B3/C3 result |
| --- | --- | ---: | --- |
| OpenGL | SampledStationaryMedium | 1 | Pending |
| OpenGL | SampledStationaryMedium | 2 | Pending |
| OpenGL | SampledStationaryMedium | 3 | Pending |
| OpenGL | SampledStationaryMedium | 4 | Pending |
| OpenGL | SampledTranslationMedium | 1 | Pending |
| OpenGL | SampledTranslationMedium | 2 | Pending |
| OpenGL | SampledTranslationMedium | 3 | Pending |
| OpenGL | SampledTranslationMedium | 4 | Pending |
| OpenGL | SampledOrderedRowsSmall | 1 | Pending |
| OpenGL | SampledOrderedRowsSmall | 2 | Pending |
| OpenGL | SampledOrderedRowsSmall | 3 | Pending |
| OpenGL | SampledOrderedRowsSmall | 4 | Pending |
| OpenGL | SampledScrolledRowsSmall | 1 | Pending |
| OpenGL | SampledScrolledRowsSmall | 2 | Pending |
| OpenGL | SampledScrolledRowsSmall | 3 | Pending |
| OpenGL | SampledScrolledRowsSmall | 4 | Pending |
| Vulkan | SampledStationaryMedium | 1 | Pending |
| Vulkan | SampledStationaryMedium | 2 | Pending |
| Vulkan | SampledStationaryMedium | 3 | Pending |
| Vulkan | SampledStationaryMedium | 4 | Pending |
| Vulkan | SampledTranslationMedium | 1 | Pending |
| Vulkan | SampledTranslationMedium | 2 | Pending |
| Vulkan | SampledTranslationMedium | 3 | Pending |
| Vulkan | SampledTranslationMedium | 4 | Pending |
| Vulkan | SampledOrderedRowsSmall | 1 | Pending |
| Vulkan | SampledOrderedRowsSmall | 2 | Pending |
| Vulkan | SampledOrderedRowsSmall | 3 | Pending |
| Vulkan | SampledOrderedRowsSmall | 4 | Pending |
| Vulkan | SampledScrolledRowsSmall | 1 | Pending |
| Vulkan | SampledScrolledRowsSmall | 2 | Pending |
| Vulkan | SampledScrolledRowsSmall | 3 | Pending |
| Vulkan | SampledScrolledRowsSmall | 4 | Pending |

## Processing and receipts

Run `:quality:component-benchmarks:processNativeComponentEvidence` once per backend with `strata.performance.request=.github/performance/issue132/native-<backend>.request.json` after all six raw invocations exist.
Require the existing consumer's archive/resource/class-tree, pacing, exact phase, per-sample count, resource-release, complete PNG inventory and backend checks to pass.
Preserve the native extraction p50/p95/p99, complete-frame p95, render-owner/process CPU, normalized allocation, GC, phase/work counts, retention, and categorized payload count/byte observations.
Keep the existing payload field names and their actual scopes:

| Requested category | Existing observation | Gap |
| --- | --- | --- |
| Source uploads | `sampledImageUploadCount`, `sourceUploadByteCount` | Values remain Pending until collection. |
| Raster uploads | `rasterUploadByteCount` | A separate categorized count is unavailable; `textureUploadCount` includes other portable transfers. |
| Lookup metadata uploads | `samplingUploadByteCount` records sampling indices and composition indices/factors | A separate categorized count is unavailable. |
| GPU-generated output uploads | Excluded from the existing CPU-upload instrumentation | Independent output count and bytes are unavailable. |

Do not rename the aggregate texture count into a categorized raster or metadata count, or infer output zeros from absent counters.
Keep full presentation GPU and GUI-only query scopes separate, with their complete actual timestamp pairs and driver metadata.
Unavailable fields remain explicit nulls with reasons; they do not become zero values or a completed required measurement.
Preserve each raw report, PNG, CPU receipt, comparison JSON, loaded archive and origin mapping, terminal receipt, launch log and SHA-256 inventory in the final receipt archive.
The existing shared processor owns medians and comparison math; this plan adds no alternate aggregator.

The latest historical 15-case B3/C3 `avgt` and `sample` data remain actual evidence for their frozen source binding.
Before retaining them for a later public revision, record the actual production and historical fixture source equivalence and preserve their original frozen collector, compiled pins and raw archive.
The existing public toolkit adds secondary-metric processing after the experiment's `c254f5e5` source; do not rebind the original receipts to that different archive.
Any relevant measured runtime, fixture or experiment-collector change requires a new complete frozen comparison rather than rebinding old measurements by assertion.
After source publication, require exact final-head JVM/quality/ABI, Kover after JVM tests, Qodana and all 22 supported Minecraft jobs, plus the required native correctness and completed selected measurements.
PR165 remains Draft and performance Squash eligibility remains Pending until this whole acceptance boundary is satisfied.
