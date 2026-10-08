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
    private val outputArguments =
        setOf(
            "-Dstrata.performance.nativeOutput=",
            "-Dstrata.canvas.shutdown.run=",
            "-Dstrata.canvas.shutdown.receipt=",
        )
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

    /**
     * Processes the profile's independent raw invocations into a new summary without replacing evidence.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val request = JvmPerformanceEvidence.readReport(Path.of(args.single()))
        val profile = PerformanceProfile.fromQuickFlag(request.get("quick")?.asString)
        val sampledImages = request.get("sampled_images")?.asBoolean ?: false
        val selection = selection(request, profile, sampledImages)
        val collector = collectorArchive()
        require(Files.isSameFile(Path.of(request.get("collector").asString), collector)) { "Specify the actual loaded collector archive" }
        val cpu = JvmPerformanceEvidence.readReport(Path.of(request.get("cpu_report").asString))
        val metadata = cpuRuntimeMetadata(cpu)
        val selected = JsonArray()
        metadata.getAsJsonArray("modules").forEach { entry ->
            if (entry.asJsonObject.get("representativeClass").asString in representatives) selected.add(entry.deepCopy())
        }
        val provenance = JsonObject().apply { add("strata", metadata.deepCopy().apply { add("modules", selected) }) }
        val arguments = mutableListOf<JsonObject>()
        val binaries = JsonArray()
        val paths = request.getAsJsonArray("runs").map { Path.of(it.asString).toAbsolutePath().normalize() }
        val imageDirectories = paths.map { checkNotNull(it.parent).resolve("images") }.toSet()
        val reports = paths.map(JvmPerformanceEvidence::readReport)
        val epoch = NativePresentationEpoch.fromWorkloadIds(reports.map { it.get("workload_id").asString }, selection, profile, sampledImages)
        val presentationPresent = presentationGpuMetricsPresent(reports)
        val summary =
            JvmPerformanceReports.summarize(
                paths,
                collector,
                contract(selection, profile, sampledImages, epoch),
                metrics + if (presentationPresent) presentationMetrics else emptyList(),
            ) { report ->
                verify(report, selection, profile, sampledImages)
                arguments.add(
                    JsonObject().apply {
                        add(
                            "jvm_arguments",
                            JsonArray().apply {
                                report.getAsJsonArray("jvm_arguments").forEach { value ->
                                    if (outputArguments.none(value.asString::startsWith)) add(value.deepCopy())
                                }
                            },
                        )
                    },
                )
                JvmPerformanceEvidence.verifyEqual(arguments, setOf("jvm_arguments"))
                binaries.add(NativePerformanceEvidence.verify(report, provenance, representatives, setOf("fabric")))
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
            }
        if (presentationPresent.not()) {
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
        summary.add("binary_receipts", binaries)
        summary.addProperty("cpu_provenance_report_sha256", ArtifactIdentity.file(Path.of(request.get("cpu_report").asString)))
        PerformanceJson.writeNew(Path.of(request.get("output").asString), summary)
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
     * Registers full presentation metrics only when every raw phase exposes their optional ancestor.
     * Legacy adapters may omit it only while GUI queries are unavailable; mixed matrices are rejected.
     */
    internal fun presentationGpuMetricsPresent(reports: List<JsonObject>): Boolean {
        val phases = reports.flatMap { report -> report.getAsJsonArray("phases").map { it.asJsonObject } }
        val present = phases.map { it.has("presentation_gpu") }
        require(present.isNotEmpty() && present.distinct().size == 1) { "Inconsistent full presentation GPU metric availability" }
        val guiAvailable = phases.map { it.getAsJsonObject("native_gpu").get("available").asBoolean }
        require(guiAvailable.distinct().size == 1) { "Inconsistent GUI GPU measurement availability" }
        if (present.first()) {
            val fullAvailable = phases.map { phase -> phase.get("presentation_gpu").let { it.isJsonNull.not() && it.asJsonObject.get("available").asBoolean } }
            require(fullAvailable.distinct().size == 1 && fullAvailable.first() == guiAvailable.first()) { "Inconsistent full presentation GPU measurement availability" }
        } else {
            require(guiAvailable.first().not()) { "Available GUI queries require complete full presentation GPU pairs" }
        }
        return present.first()
    }

    private fun collectorArchive(): Path {
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

    private fun contract(
        selection: PerformanceSelection,
        profile: PerformanceProfile,
        sampledImages: Boolean,
        epoch: NativePresentationEpoch,
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
                (if (epoch == NativePresentationEpoch.Paced) setOf("inactivity_mode") else emptySet()),
            setOf("samples", "framebuffer_width", "framebuffer_height") + if (epoch == NativePresentationEpoch.Paced) setOf("pacing") else emptySet(),
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
        when (epoch) {
            NativePresentationEpoch.Paced -> NativePacingEvidence.verify(report)
            NativePresentationEpoch.Legacy -> require(report.has("inactivity_mode").not() && report.has("borrowed_options_restored").not() && phases.none { it.has("pacing") }) {
                "Live pacing evidence requires the paced native fixture epoch"
            }
        }
        require(phases.map { it.get("case").asString to it.get("gui_scale").asInt }.toSet() == selection.ids.flatMap { name -> scales.map { name to it } }.toSet()) { "Changed native component matrix" }
        require(phases.size == selection.ids.size * scales.size) { "Duplicate native component intervals" }
        phases.forEach { phase ->
            require(phase.get("operation").asString.contentEquals("presented") && phase.get("samples").asInt == plan.samples)
            verifyGpu(phase, plan.samples)
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

    private fun verifyGpu(
        phase: JsonObject,
        samples: Int,
    ) {
        val gpu = phase.getAsJsonObject("native_gpu")
        verifyGpuResult(gpu, samples)
        val presentation = phase.get("presentation_gpu")?.takeUnless { it.isJsonNull }?.asJsonObject
        if (gpu.get("available").asBoolean) {
            require(presentation != null && presentation.get("available").asBoolean) { "Available GUI queries require complete full presentation GPU pairs" }
            require(presentation.get("scope") != gpu.get("scope")) { "Full presentation GPU scope cannot be the GUI-only scope" }
        }
        presentation?.let {
            require(it.get("available") == gpu.get("available")) { "Inconsistent GPU scope availability" }
            verifyGpuResult(it, samples)
        }
    }

    private fun verifyGpuResult(
        gpu: JsonObject,
        samples: Int,
    ) {
        if (gpu.get("available").asBoolean) {
            require(gpu.get("samples").asInt == samples)
            val period = gpu.get("timestamp_period_ns").asDouble
            require(period.isFinite() && 0.0 < period && gpu.get("scope").asString.isNotBlank())
            listOf("duration", "operation_to_completion_observation").forEach { field ->
                val distribution = gpu.getAsJsonObject(field)
                require(distribution != null && distribution.get("samples")?.asInt == samples) { "Incomplete native GPU interval" }
            }
        } else {
            require(gpu.get("reason").asString.isNotBlank())
            require(gpu.get("duration").isJsonNull && gpu.get("operation_to_completion_observation").isJsonNull)
        }
    }
}
