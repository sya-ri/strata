package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceEvidence
import dev.s7a.strata.performance.JvmPerformanceMeter
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.NativePerformanceEvidence
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
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
            "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
            "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
        )
    private val cases =
        checkNotNull(javaClass.getResourceAsStream("/native-components.tsv"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readLines() }
            .toSet()
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
        ) +
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

    /**
     * Processes three independent raw invocations into a new summary without replacing evidence.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val request = JvmPerformanceEvidence.readReport(Path.of(args.single()))
        val collectorType = JvmPerformanceMeter::class.java
        val collector =
            Path.of(
                collectorType.protectionDomain.codeSource.location
                    .toURI(),
            )
        require(Files.isSameFile(Path.of(request.get("collector").asString), collector)) { "Specify the actual loaded collector archive" }
        val cpu = JvmPerformanceEvidence.readReport(Path.of(request.get("cpu_report").asString))
        val metadata = cpu.getAsJsonObject("strata")
        val selected = JsonArray()
        metadata.getAsJsonArray("modules").forEach { entry ->
            if (entry.asJsonObject.get("representativeClass").asString in representatives) selected.add(entry.deepCopy())
        }
        val provenance = JsonObject().apply { add("strata", metadata.deepCopy().apply { add("modules", selected) }) }
        val arguments = mutableListOf<JsonObject>()
        val binaries = JsonArray()
        val paths = request.getAsJsonArray("runs").map { Path.of(it.asString).toAbsolutePath().normalize() }
        val imageDirectories = paths.map { checkNotNull(it.parent).resolve("images") }.toSet()
        val summary =
            JvmPerformanceReports.summarize(
                paths,
                collector,
                contract(),
                metrics,
            ) { report ->
                verify(report)
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
        summary.add("binary_receipts", binaries)
        summary.addProperty("cpu_provenance_report_sha256", ArtifactIdentity.file(Path.of(request.get("cpu_report").asString)))
        PerformanceJson.writeNew(Path.of(request.get("output").asString), summary)
    }

    private fun contract(): PerformanceReportContract =
        PerformanceReportContract(
            "native-components-presented-v1",
            listOf("case", "operation", "gui_scale"),
            cases.size * 4,
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
                "fixture_archive_sha256",
                "warmup",
                "settle_frames",
                "preparation_timeout_ms",
            ),
            setOf("samples", "framebuffer_width", "framebuffer_height"),
        )

    /**
     * Requires the reviewed case/scale matrix, complete presentation boundaries and balanced native release.
     */
    internal fun verify(report: JsonObject) {
        require(report.get("status").asString.contentEquals("passed"))
        require(report.get("framebuffer_width").asInt == 1920 && report.get("framebuffer_height").asInt == 1080)
        require(report.get("vsync").asBoolean.not() && report.get("framerate_limit").asInt == 120)
        require(report.get("warmup").asInt == 30 && report.get("settle_frames").asInt == 8 && report.get("preparation_timeout_ms").asLong == 120_000L)
        val phases = report.getAsJsonArray("phases").map { it.asJsonObject }
        require(phases.map { it.get("case").asString to it.get("gui_scale").asInt }.toSet() == cases.flatMap { name -> (1..4).map { name to it } }.toSet()) { "Changed native component matrix" }
        phases.forEach { phase ->
            require(phase.get("operation").asString.contentEquals("presented") && phase.get("samples").asInt == 60)
            require(phase.getAsJsonObject("frame_interval").get("samples").asInt == 60)
            require(phase.getAsJsonObject("native_counter_delta").get("renderExtractionCount").asInt == 60)
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
            require(0 < release.get("${kind}_opened").asInt && release.get("${kind}_opened") == release.get("${kind}_closed")) { "Incomplete native resource release" }
        }
    }
}
