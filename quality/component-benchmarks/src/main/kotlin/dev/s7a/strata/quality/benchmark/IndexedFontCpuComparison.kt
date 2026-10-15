package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceEvidence
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.nio.file.Files
import java.nio.file.Path

/**
 * Processes all original 47 operation reports and 32 separate lifetime reports through the shared processor.
 * It never samples an operation, changes a timer, pools per-run quantiles or rewrites original evidence.
 */
public object IndexedFontCpuComparison {
    /**
     * External report identity shared with the unchanged original collector's serialized header.
     */
    public const val workloadId: String = "indexed-font-original-operations-v1"

    /**
     * Accepts original baseline3/candidate3 reports, the selected collector JAR and a fresh comparison path.
     * Setup/teardown projections retain the source file's exact SHA-256 and every original raw counter.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 8)
        val paths = args.take(6).map { Path.of(it).toAbsolutePath().normalize() }
        val collector = Path.of(args[6])
        val output = Path.of(args[7]).toAbsolutePath().normalize()
        require(Files.exists(output).not())
        val result =
            JvmPerformanceReports.compare(paths.take(3), paths.drop(3), collector, contract(47), metrics) {
                verifyOperations(it)
            }
        val directory = Files.createDirectory(checkNotNull(output.parent).resolve(output.fileName.toString() + "-lifetimes"))
        val lifetimes =
            paths.mapIndexed { index, path ->
                val report = JvmPerformanceEvidence.readReport(path)
                verifyOperations(report)
                val source =
                    JsonObject().apply {
                        addProperty("path", path.toString())
                        addProperty("sha256", ArtifactIdentity.file(path))
                    }
                val target = directory.resolve("run-$index.json")
                PerformanceJson.writeNew(target, lifecycleProjection(report, source))
                target
            }
        val lifecycle =
            JvmPerformanceReports.compare(lifetimes.take(3), lifetimes.drop(3), collector, contract(32), metrics) {
                val source = it.getAsJsonObject("lifecycle_projection_source")
                val path = Path.of(source.get("path").asString)
                require(ArtifactIdentity.file(path) == source.get("sha256").asString)
                val original = JvmPerformanceEvidence.readReport(path)
                verifyOperations(original)
                require(it.getAsJsonArray("phases") == original.getAsJsonArray("setup_teardown"))
            }
        result.add("setup_teardown", lifecycle)
        result.addProperty("lifecycle_scope", "Original one-sample setup/teardown per owner; separate from operation and JMH intervals")
        PerformanceJson.writeNew(output, result)
    }

    /**
     * Makes a detached raw projection for shared processing, retaining every original lifetime value.
     * The original report remains unchanged and incomplete, duplicate or resampled lifetime rows fail.
     */
    internal fun lifecycleProjection(
        report: JsonObject,
        source: JsonObject,
    ): JsonObject {
        val phases = report.getAsJsonArray("setup_teardown")
        verifyPhases(phases, 32, 0, 1)
        return report.deepCopy().apply {
            remove("source_receipt")
            add("phases", phases.deepCopy())
            add("lifecycle_projection_source", source.deepCopy())
        }
    }

    private fun verifyOperations(report: JsonObject) {
        require(report.get("contract").asString == "indexed-font-supplemental-cpu-v1")
        require(report.get("warmup").asInt == 30 && report.get("samples").asInt == 60 && report.get("controls").asInt == 32)
        LoadedArtifactMetadata.verifyComplete(report.getAsJsonObject("runtime_metadata"))
        verifyPhases(report.getAsJsonArray("phases"), 47, 30, 60)
        verifyPhases(report.getAsJsonArray("setup_teardown"), 32, 0, 1)
    }

    private fun verifyPhases(
        phases: JsonArray,
        count: Int,
        warmup: Int,
        samples: Int,
    ) {
        require(phases.size() == count)
        val names = phases.map { it.asJsonObject.get("name").asString }
        require(names.distinct().size == count)
        require(phases.all { it.asJsonObject.get("warmup").asInt == warmup })
        IndexedFontCpuReportValidation.verifyPhases(phases, names, samples)
    }

    private fun contract(count: Int): PerformanceReportContract =
        PerformanceReportContract(
            workloadId = workloadId,
            phaseKeys = listOf("name"),
            phaseCount = count,
            reportConditions = setOf("contract", "warmup", "samples", "controls", "fixture_hashes", "inputs", "gpu_upload_native_timing"),
            phaseConditions = setOf("samples", "warmup"),
            variantReportFields = setOf("runtime_metadata"),
        )

    private val metrics =
        listOf(
            PerformanceReportMetric("wall_mean_ns", listOf("wall_total_ns"), divisorPath = listOf("samples")),
            PerformanceReportMetric("owner_cpu_mean_ns", listOf("owner_thread_cpu_ns"), divisorPath = listOf("samples")),
            PerformanceReportMetric("owner_allocation_mean_bytes", listOf("owner_thread_allocated_bytes"), divisorPath = listOf("samples")),
            PerformanceReportMetric("process_cpu_ns_per_sample", listOf("process_cpu_ns"), divisorPath = listOf("samples")),
            PerformanceReportMetric("all_threads_allocation_bytes_per_sample", listOf("all_threads_allocated_bytes"), divisorPath = listOf("samples")),
            PerformanceReportMetric("gc_collections_per_sample", listOf("gc_collections_during_samples"), divisorPath = listOf("samples")),
            PerformanceReportMetric("gc_time_ms_per_sample", listOf("gc_collection_time_ms_during_samples"), divisorPath = listOf("samples")),
        ) +
            listOf(
                Triple("wall_distribution", "wall", "ns"),
                Triple("owner_thread_cpu_distribution", "owner_cpu", "ns"),
                Triple("owner_thread_allocation_distribution", "owner_allocation", "bytes"),
            ).flatMap { (field, label, unit) ->
                listOf("p50", "p95", "p99").map { quantile ->
                    PerformanceReportMetric("${label}_${quantile}_$unit", listOf(field, "${quantile}_ns"))
                }
            }
}
