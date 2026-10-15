package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceEvidence
import dev.s7a.strata.performance.JvmPerformanceMeter
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.NativePerformanceEvidence
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceProfile
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import dev.s7a.strata.performance.PerformanceSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Registers settled native component presentation with the shared evidence processor.
 * The supplied real JVM receipt establishes runtime binary provenance only; its timings are not compared with native frames.
 */
internal object NativeComponentPerformanceEvidence {
    private val representatives =
        setOf(
            "dev.s7a.strata.render.DrawImage",
            "dev.s7a.strata.runtime.UiSession",
            "dev.s7a.strata.runtime.headless.HeadlessImage",
            "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
            "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
        )
    private val cases =
        checkNotNull(javaClass.getResourceAsStream("/native-components.tsv"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
            .toSet()
    private val sampledCases = checkNotNull(javaClass.getResourceAsStream("/native-sampled-images.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }.toSet()
    private val metrics =
        listOf(
            PerformanceReportMetric("extraction_p50_ns", listOf("wall_p50_ns")),
            PerformanceReportMetric("extraction_p95_ns", listOf("wall_p95_ns")),
            PerformanceReportMetric("extraction_p99_ns", listOf("wall_p99_ns")),
            PerformanceReportMetric("frame_p95_ns", listOf("frame_interval", "p95_ns")),
            PerformanceReportMetric("frame_cpu_p95_ns", listOf("render_thread_frame_cpu", "p95_ns")),
            PerformanceReportMetric("allocated_bytes_per_sample", listOf("owner_thread_allocated_bytes"), listOf("samples")),
            PerformanceReportMetric("owner_cpu_ns_per_sample", listOf("owner_thread_cpu_ns"), listOf("samples")),
            PerformanceReportMetric("process_cpu_ns_per_sample", listOf("process_cpu_ns"), listOf("samples")),
            PerformanceReportMetric("gc_collections", listOf("gc_collections_during_samples")),
            PerformanceReportMetric("gc_time_ms", listOf("gc_collection_time_ms_during_samples")),
            PerformanceReportMetric("draw_commands", listOf("draw_commands")),
            PerformanceReportMetric("retained_image_entries", listOf("native_retained", "sampledImageRetainedEntryCount")),
            PerformanceReportMetric("retained_image_bytes", listOf("native_retained", "sampledImageRetainedByteCount")),
            PerformanceReportMetric("gui_gpu_p50_ns", listOf("native_gpu", "duration", "p50_ns")),
            PerformanceReportMetric("gui_gpu_p95_ns", listOf("native_gpu", "duration", "p95_ns")),
            PerformanceReportMetric("gui_gpu_p99_ns", listOf("native_gpu", "duration", "p99_ns")),
            PerformanceReportMetric("gui_completion_observation_p95_ns", listOf("native_gpu", "operation_to_completion_observation", "p95_ns")),
        ) +
            listOf("sourceUploadByteCount", "rasterUploadByteCount", "samplingUploadByteCount", "tintFallbackCount", "alphaCutoffFallbackCount", "otherIneligibleFallbackCount").map {
                PerformanceReportMetric("${it}_per_sample", listOf("native_payload", it), listOf("samples"))
            } +
            listOf(
                "renderExtractionCount",
                "hostFrameCount",
                "framePreparationCount",
                "portableRasterizationCount",
                "textureUploadCount",
                "sampledImageDirectHitCount",
                "sampledImageDirectMissCount",
                "sampledImageUploadCount",
                "sampledImageDrawCount",
                "sampledImageEvictionCount",
                "sampledImageCapacityFallbackCount",
                "sampledImageIneligibleFallbackCount",
            ).map { PerformanceReportMetric("${it}_per_sample", listOf("native_counter_delta", it), listOf("samples")) } +
            listOf("RootEvaluation", "ContentEvaluation", "Measure", "Layout", "Paint", "OverlayPaint", "FrameCacheHit").map {
                PerformanceReportMetric("${it}_per_sample", listOf("diagnostics", "counts", it), listOf("samples"))
            }

    private val presentationMetrics =
        listOf(
            PerformanceReportMetric("presentation_gpu_p50_ns", listOf("presentation_gpu", "duration", "p50_ns")),
            PerformanceReportMetric("presentation_gpu_p95_ns", listOf("presentation_gpu", "duration", "p95_ns")),
            PerformanceReportMetric("presentation_gpu_p99_ns", listOf("presentation_gpu", "duration", "p99_ns")),
        )

    private val pacingMetrics =
        listOf("frame_interval", "render_thread_frame_cpu", "owner_thread_cpu_distribution").flatMap { scope ->
            listOf("p50_ns", "p99_ns", "total_ns", "max_ns").map { metric ->
                PerformanceReportMetric("${scope}_$metric", listOf(scope, metric))
            } + PerformanceReportMetric("${scope}_mean_ns", listOf(scope, "total_ns"), listOf(scope, "samples"))
        } +
            listOf(
                PerformanceReportMetric("extraction_mean_ns", listOf("wall_total_ns"), listOf("samples")),
                PerformanceReportMetric("all_threads_allocated_bytes_per_sample", listOf("all_threads_allocated_bytes"), listOf("samples")),
            ) +
            listOf("p50", "p95", "p99", "max").map { percentile ->
                PerformanceReportMetric("owner_allocation_${percentile}_bytes", listOf("owner_thread_allocation_distribution", "${percentile}_ns"))
            }

    /**
     * Processes independent raw invocations through the shared summary or comparison API without replacing evidence.
     * Each comparison side supplies its actual CPU receipt; archive, matrix, pacing and image checks apply to both sides.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val request = JvmPerformanceEvidence.readReport(Path.of(args.single()))
        val command = NativeEvidenceCommand.decode(request.get("command")?.asString)
        val backend = request.get("backend")?.asString?.let(NativeEvidenceBackend::decode)
        require(command == NativeEvidenceCommand.Summary || backend != null) { "A comparison must declare its actual native backend" }
        val profile = PerformanceProfile.fromQuickFlag(request.get("quick")?.asString)
        val sampledImages = request.get("sampled_images")?.asBoolean ?: false
        val selection = selection(request, profile, sampledImages)
        val collector = collectorArchive
        require(Files.isSameFile(Path.of(request.get("collector").asString), collector)) { "Specify the actual loaded collector archive" }
        val inputs = inputs(request, command, profile.plan().repetitions)
        val cpuHashes = inputs.associate { it.second to ArtifactIdentity.file(it.second) }
        val provenance = cpuProvenanceByRun(inputs)
        val binaries = JsonArray()
        val paths = inputs.flatMap { it.first }
        val imageDirectories = paths.map { checkNotNull(it.parent).resolve("images") }.toSet()
        val reports = paths.map(JvmPerformanceEvidence::readReport)
        val epoch = NativePresentationEpoch.fromWorkloadIds(reports.map { it.get("workload_id").asString }, selection, profile, sampledImages)
        if (command == NativeEvidenceCommand.PacingComparison) {
            require(profile == PerformanceProfile.Standard && epoch == NativePresentationEpoch.TimedPaced) { "Pacing policy comparison requires the current timed Standard fixture" }
            NativePacingEvidence.verifyPolicyComparison(reports.take(3), reports.drop(3))
        }
        val presentationPresent = NativeComponentGpuEvidence.presentationGpuMetricsPresent(reports)
        val validate = validation(request, provenance, imageDirectories, binaries, backend)
        val reportContract = contract(selection, profile, sampledImages, epoch, command)
        val reportMetrics = metrics + (if (presentationPresent) presentationMetrics else emptyList()) + if (command == NativeEvidenceCommand.PacingComparison) pacingMetrics else emptyList()
        val summary =
            when (command) {
                NativeEvidenceCommand.Summary -> JvmPerformanceReports.summarize(paths, collector, reportContract, reportMetrics, validate)
                NativeEvidenceCommand.Comparison, NativeEvidenceCommand.PacingComparison -> JvmPerformanceReports.compare(inputs[0].first, inputs[1].first, collector, reportContract, reportMetrics, validate)
            }
        when (command) {
            NativeEvidenceCommand.Summary -> {
                recordPresentationAvailability(summary, presentationPresent)
                summary.addProperty("cpu_provenance_report_sha256", cpuHashes.getValue(inputs.single().second))
            }

            NativeEvidenceCommand.Comparison, NativeEvidenceCommand.PacingComparison -> {
                completeComparison(summary, inputs, cpuHashes, presentationPresent, reportContract.phaseCount)
                if (command == NativeEvidenceCommand.PacingComparison) {
                    NativePacingEvidence.verifyPolicyBinaries(binaries)
                    summary.addProperty("comparison_scope", "AFK to MINIMIZED inactivity policy on identical runtime and fixture bytes; live pacing observations are outcomes")
                }
            }
        }
        if (epoch == NativePresentationEpoch.TimedPaced) recordCollectionRuns(summary, reports)
        summary.add("binary_receipts", binaries)
        cpuHashes.forEach { (path, hash) -> require(ArtifactIdentity.file(path).contentEquals(hash)) { "Changed CPU provenance receipt" } }
        PerformanceJson.writeNew(Path.of(request.get("output").asString), summary)
    }

    /**
     * Retains invocation observations without combining whole-fixture elapsed with sampled operation metrics.
     */
    private fun recordCollectionRuns(
        summary: JsonObject,
        reports: List<JsonObject>,
    ) {
        summary.add(
            "collection_runs",
            JsonArray().apply {
                reports.forEach { report ->
                    add(
                        JsonObject().apply {
                            listOf("run_id", "inactivity_mode", "collection_elapsed_ns", "collection_elapsed_scope").forEach { add(it, report.get(it).deepCopy()) }
                            add(
                                "pacing_phases",
                                JsonArray().apply {
                                    report.getAsJsonArray("phases").forEach { phase ->
                                        add(JsonObject().apply { listOf("case", "gui_scale", "pacing").forEach { add(it, phase.asJsonObject.get(it).deepCopy()) } })
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    /**
     * Decodes complete independent groups and their CPU receipts without changing the requested sampling profile.
     */
    private fun inputs(
        request: JsonObject,
        command: NativeEvidenceCommand,
        repetitions: Int,
    ): List<Pair<List<Path>, Path>> =
        when (command) {
            NativeEvidenceCommand.Summary -> listOf(request)
            NativeEvidenceCommand.Comparison, NativeEvidenceCommand.PacingComparison -> listOf(request.getAsJsonObject("baseline"), request.getAsJsonObject("candidate"))
        }.map { input ->
            val paths = input.getAsJsonArray("runs").map { Path.of(it.asString).toAbsolutePath().normalize() }
            require(paths.size == repetitions) { "Incomplete native repetition group" }
            paths to Path.of(input.get("cpu_report").asString).toAbsolutePath().normalize()
        }

    /**
     * Captures invocation-owned binary and argument observations; both operations share all native admission checks.
     */
    private fun validation(
        request: JsonObject,
        provenance: Map<UUID, JsonObject>,
        imageDirectories: Set<Path>,
        binaries: JsonArray,
        backend: NativeEvidenceBackend?,
    ): (JsonObject) -> Unit {
        val profile = PerformanceProfile.fromQuickFlag(request.get("quick")?.asString)
        val sampledImages = request.get("sampled_images")?.asBoolean ?: false
        val selection = selection(request, profile, sampledImages)
        val arguments = mutableListOf<JsonObject>()
        return { report ->
            verify(report, selection, profile, sampledImages)
            backend?.verify(report)
            binaries.add(NativePerformanceEvidence.verify(report, provenance.getValue(UUID.fromString(report.get("run_id").asString)), representatives, setOf("fabric")))
            val fixture = Path.of(URI(report.get("fixture_archive").asString))
            require(ArtifactIdentity.file(fixture).contentEquals(report.get("fixture_archive_sha256").asString)) { "Changed native fixture archive" }
            val images = Path.of(report.get("image_directory").asString).toAbsolutePath().normalize()
            require(images in imageDirectories) { "Unexpected native image directory" }
            report.getAsJsonArray("phases").forEach { entry ->
                val phase = entry.asJsonObject
                val name = Path.of(phase.get("png_file").asString)
                require(name.nameCount == 1 && name.fileName.toString().endsWith(".png") && name.isAbsolute.not())
                require(ArtifactIdentity.file(images.resolve(name)).contentEquals(phase.get("png_sha256").asString)) { "Changed native image" }
            }
            arguments.add(NativeComponentJvmArguments.read(report, NativeEvidenceCommand.decode(request.get("command")?.asString)))
            JvmPerformanceEvidence.verifyEqual(arguments, setOf("jvm_arguments"))
        }
    }

    /**
     * Adds the shared exact image comparison and explicit unavailable full-GPU fields to both processed sides.
     */
    private fun completeComparison(
        comparison: JsonObject,
        inputs: List<Pair<List<Path>, Path>>,
        cpuHashes: Map<Path, String>,
        presentationPresent: Boolean,
        phaseCount: Int,
    ) {
        val images = JsonArray()
        inputs[0].first.zip(inputs[1].first).forEach { (before, after) ->
            images.add(JvmPerformanceReports.compareImages(checkNotNull(before.parent).resolve("images"), checkNotNull(after.parent).resolve("images"), phaseCount))
        }
        comparison.add("image_comparisons", images)
        listOf("before", "after").zip(inputs).forEach { (field, input) ->
            val side = comparison.getAsJsonObject(field)
            recordPresentationAvailability(side, presentationPresent)
            side.addProperty("cpu_provenance_report_sha256", cpuHashes.getValue(input.second))
        }
        if (presentationPresent.not()) {
            comparison.getAsJsonArray("phases").forEach { row ->
                listOf("before", "after").forEach { field ->
                    presentationMetrics.forEach { metric -> row.asJsonObject.getAsJsonObject(field).add(metric.name, JsonNull.INSTANCE) }
                }
            }
        }
    }

    /**
     * Binds each independent native invocation to its side's actual CPU archive metadata.
     * The invocation-owned map rejects duplicate UUIDs rather than borrowing another side's runtime identity.
     */
    internal fun cpuProvenanceByRun(inputs: List<Pair<List<Path>, Path>>): Map<UUID, JsonObject> {
        val provenance = mutableMapOf<UUID, JsonObject>()
        inputs.forEach { (paths, cpuPath) ->
            val cpu = nativeCpuProvenance(JvmPerformanceEvidence.readReport(cpuPath))
            paths.forEach { path ->
                val id = UUID.fromString(JvmPerformanceEvidence.readReport(path).get("run_id").asString)
                require(provenance.put(id, cpu) == null) { "Duplicate native invocation across runtime bindings" }
            }
        }
        return provenance
    }

    /**
     * Projects the five measured runtime representatives from the validated CPU receipt without modifying it.
     */
    private fun nativeCpuProvenance(cpu: JsonObject): JsonObject {
        val metadata = cpuRuntimeMetadata(cpu)
        val selected = JsonArray()
        metadata.getAsJsonArray("modules").forEach { entry ->
            if (entry.asJsonObject.get("representativeClass").asString in representatives) selected.add(entry.deepCopy())
        }
        return JsonObject().apply { add("strata", metadata.deepCopy().apply { add("modules", selected) }) }
    }

    /**
     * Records explicit unavailable values for the legacy matrix after raw GPU availability has been validated.
     */
    private fun recordPresentationAvailability(
        summary: JsonObject,
        presentationPresent: Boolean,
    ) {
        if (presentationPresent) return
        summary.getAsJsonArray("phases").forEach { row ->
            val values = row.asJsonObject.getAsJsonObject("metrics")
            presentationMetrics.forEach { metric -> values.add(metric.name, JsonNull.INSTANCE) }
        }
        summary.add(
            "presentation_gpu",
            JsonObject().apply {
                addProperty("available", false)
                addProperty("reason", "The measured adapter exposes no full presentation GPU scope; GUI GPU queries are unavailable.")
            },
        )
    }

    /**
     * Reads loaded metadata from one actual legacy JVM report or current JMH receipt without rewriting it.
     * Current receipts must retain successful per-iteration fork verification; archive checks remain native admission's responsibility.
     */
    internal fun cpuRuntimeMetadata(cpu: JsonObject): JsonObject {
        require(cpu.get("status")?.asString?.contentEquals("passed") == true) { "CPU provenance invocation did not pass" }
        val fields = listOf("strata", "runtime_metadata").filter(cpu::has)
        require(fields.size == 1) { "Expected one actual CPU runtime metadata field" }
        if (cpu.has("runtime_metadata")) {
            require(cpu.get("contract")?.asString?.contentEquals("strata-jmh-v1") == true) { "Unsupported CPU JMH receipt" }
            require(cpu.get("fork_verification")?.asString?.contentEquals("loaded-artifacts-per-iteration-v1") == true) { "Missing actual CPU fork verification" }
        }
        return cpu.getAsJsonObject(fields.single()).also(LoadedArtifactMetadata::verifyComplete)
    }

    /**
     * Resolves the actual loaded collector when the request reaches archive admission.
     */
    private val collectorArchive: Path
        get() {
            val type = JvmPerformanceMeter::class.java
            return Path.of(
                type.protectionDomain.codeSource.location
                    .toURI(),
            )
        }

    private fun selection(
        request: JsonObject,
        profile: PerformanceProfile,
        sampledImages: Boolean,
    ): PerformanceSelection {
        val corpus = if (sampledImages) sampledCases else cases
        return PerformanceSelection(corpus, request.get("workloads")?.asString ?: if (profile == PerformanceProfile.Quick) corpus.first() else null)
    }

    internal fun contract(
        selection: PerformanceSelection,
        profile: PerformanceProfile,
        sampledImages: Boolean,
        epoch: NativePresentationEpoch,
        command: NativeEvidenceCommand = NativeEvidenceCommand.Summary,
    ): PerformanceReportContract =
        PerformanceReportContract(
            epoch.workloadId(selection, profile, sampledImages),
            listOf("case", "operation", "gui_scale"),
            selection.ids.size * profile.viewports((1..4).toList()).size,
            setOf(
                "minecraft_version",
                "java",
                "vm",
                "os",
                "os_version",
                "architecture",
                "cpu_model",
                "available_processors",
                "max_heap_bytes",
                "framebuffer_width",
                "framebuffer_height",
                "vsync",
                "framerate_limit",
                "scope",
                "native_backend",
                "native_driver",
                "native_gpu_requested",
                "fixture_archive_sha256",
                "warmup",
                "settle_frames",
                "preparation_timeout_ms",
            ) +
                (if (profile == PerformanceProfile.Quick) setOf("measurement_profile") else emptySet()) +
                (if (epoch != NativePresentationEpoch.Legacy && command != NativeEvidenceCommand.PacingComparison) setOf("inactivity_mode") else emptySet()) +
                (if (epoch == NativePresentationEpoch.TimedPaced) setOf("collection_elapsed_scope") else emptySet()),
            setOf("samples", "framebuffer_width", "framebuffer_height") + if (epoch != NativePresentationEpoch.Legacy && command != NativeEvidenceCommand.PacingComparison) setOf("pacing") else emptySet(),
            variantReportFields = if (command == NativeEvidenceCommand.PacingComparison) setOf("inactivity_mode") else emptySet(),
            repetitions = profile.plan().repetitions,
        )

    /**
     * Requires the reviewed case/scale matrix, complete presentation boundaries and balanced native release.
     */
    internal fun verify(
        report: JsonObject,
        selection: PerformanceSelection = PerformanceSelection(cases),
        profile: PerformanceProfile = PerformanceProfile.Standard,
        sampledImages: Boolean = false,
    ) {
        val epoch = NativePresentationEpoch.fromWorkloadId(report.get("workload_id").asString, selection, profile, sampledImages)
        report.getAsJsonArray("selected_cases")?.let { declared ->
            require(declared.size() == selection.ids.size && declared.map { it.asString }.toSet() == selection.ids) { "Changed native selection" }
        }
        require(report.get("status").asString.contentEquals("passed"))
        if (profile == PerformanceProfile.Quick) require(report.get("measurement_profile").asString == profile.name)
        require(report.get("framebuffer_width").asInt == 1920 && report.get("framebuffer_height").asInt == 1080)
        require(report.get("vsync").asBoolean.not() && report.get("framerate_limit").asInt == 120)
        val plan = profile.plan()
        val scales = profile.viewports((1..4).toList())
        require(report.get("warmup").asInt == plan.warmup && report.get("settle_frames").asInt == 8 && report.get("preparation_timeout_ms").asLong == plan.preparationTimeoutMillis)
        val phases = report.getAsJsonArray("phases").map { it.asJsonObject }
        epoch.verifyPacing(report, phases)
        require(phases.map { it.get("case").asString to it.get("gui_scale").asInt }.toSet() == selection.ids.flatMap { name -> scales.map { name to it } }.toSet()) { "Changed native component matrix" }
        require(phases.size == selection.ids.size * scales.size) { "Duplicate native component intervals" }
        phases.forEach { phase ->
            require(phase.get("operation").asString.contentEquals("presented") && phase.get("samples").asInt == plan.samples)
            NativeComponentGpuEvidence.verifyGpu(phase, plan.samples)
            require(phase.getAsJsonObject("frame_interval").get("samples").asInt == plan.samples)
            require(phase.getAsJsonObject("native_counter_delta").get("renderExtractionCount").asInt == plan.samples)
            require(
                phase
                    .getAsJsonObject("diagnostics")
                    .get("node_inventory_truncated")
                    .asBoolean
                    .not(),
            )
        }
        val release = report.getAsJsonObject("native_resource_release")
        listOf("leases", "renderers").forEach { kind ->
            require(0 <= release.get("${kind}_opened").asInt && release.get("${kind}_opened") == release.get("${kind}_closed"))
            if ("NativeCanvas" in selection.ids) require(0 < release.get("${kind}_opened").asInt) { "Incomplete native resource release" }
        }
    }
}
