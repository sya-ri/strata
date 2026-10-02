package dev.s7a.strata.integration.paper

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.integration.performance.ServerPerformanceBridge
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceEvidence
import dev.s7a.strata.performance.JvmPerformanceMeter
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.net.URI
import java.nio.file.Path

/**
 * Fixture-specific Paper assertions and JVM entry point, delegating all raw loading and aggregation to the kit.
 * The request names three independent performance directories, the selected collector and a new output file.
 * Real plugin archives must remain available; copied invocation IDs and changed fixtures cannot certify a run.
 */
public object PaperPerformanceEvidence {
    private val metrics =
        listOf(
            PerformanceReportMetric("p50_ns", listOf("wall_p50_ns")),
            PerformanceReportMetric("p95_ns", listOf("wall_p95_ns")),
            PerformanceReportMetric("p99_ns", listOf("wall_p99_ns")),
            PerformanceReportMetric("owner_cpu_ns_per_sample", listOf("owner_thread_cpu_ns"), listOf("samples")),
            PerformanceReportMetric("allocation_bytes_per_sample", listOf("owner_thread_allocated_bytes"), listOf("samples")),
            PerformanceReportMetric("gc_collections", listOf("gc_collections_during_samples")),
        )

    /**
     * Reads one request after the host exits and publishes complete shared-kit summaries without overwriting evidence.
     * Absolute operation costs do not decide success, and these reports do not measure scheduling gaps or client latency.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Provide one Paper evidence request" }
        val request = JvmPerformanceEvidence.readReport(Path.of(arguments.single()))
        val collector = Path.of(request.get("collector").asString)
        val directories = request.getAsJsonArray("runs").map { Path.of(it.asString) }
        val fixture =
            LoadedArtifactMetadata
                .capture(
                    PaperPerformanceEvidence::class.java.classLoader,
                    mapOf("fixture" to ServerPerformanceBridge::class.java.name),
                    setOf("fixture"),
                ).also(LoadedArtifactMetadata::verifyComplete)
        val fixtureHash =
            fixture
                .getAsJsonArray("modules")
                .single()
                .asJsonObject
                .getAsJsonObject("classTree")
                .get("sha256")
                .asString
        val result =
            JsonObject().apply {
                addProperty("status", "passed")
                addProperty("contract", "strata-paper-owner-summary-v1")
                add(
                    "workloads",
                    JsonArray().apply {
                        PaperPerformanceWorkload.entries.forEach { workload ->
                            add(
                                JvmPerformanceReports.summarize(
                                    directories.map { it.resolve("${workload.interval}.json") },
                                    collector,
                                    PerformanceReportContract(
                                        "strata-paper-primary-owner-${workload.interval}-v1",
                                        listOf("name"),
                                        1,
                                        setOf("host", "warmup", "required_repetitions", "environment", "runtime_identity"),
                                        setOf("samples"),
                                    ),
                                    metrics,
                                ) { report ->
                                    require(report.get("host").asString.contentEquals("paper-primary-owner"))
                                    require(report.get("warmup").asInt == 30 && report.get("required_repetitions").asInt == 3)
                                    val phase = report.getAsJsonArray("phases").single().asJsonObject
                                    require(phase.get("name").asString.contentEquals(workload.interval) && phase.get("samples").asInt == 60)
                                    verifyArchives(report, collector, fixtureHash)
                                },
                            )
                        }
                    },
                )
            }
        PerformanceJson.writeNew(Path.of(request.get("output").asString), result)
    }

    private fun verifyArchives(
        report: JsonObject,
        collector: Path,
        fixtureHash: String,
    ) {
        val runtime = report.getAsJsonObject("loaded_runtime")
        LoadedArtifactMetadata.verifyComplete(runtime)
        val expected =
            mapOf(
                "paper-api" to "dev.s7a.strata.paper.PaperUi",
                "paper-runtime" to "dev.s7a.strata.runtime.paper.PaperScreens",
                "api" to "dev.s7a.strata.ui.UiDefinition",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "remote" to "dev.s7a.strata.runtime.remote.RemoteServerSession",
                "collector" to JvmPerformanceMeter::class.java.name,
                "fixture" to ServerPerformanceBridge::class.java.name,
            )
        val modules = runtime.getAsJsonArray("modules").map { it.asJsonObject }
        require(modules.size == expected.size && modules.associate { it.get("module").asString to it.get("representativeClass").asString } == expected)
        val identity = JsonObject()
        modules.forEach { module ->
            val source = module.getAsJsonObject("codeSource")
            val actualHash = ArtifactIdentity.file(Path.of(URI(source.get("url").asString)))
            require(actualHash.contentEquals(source.get("sha256").asString)) { "The measured server archive changed" }
            identity.addProperty(module.get("representativeClass").asString, actualHash)
        }
        require(identity.get(JvmPerformanceMeter::class.java.name).asString.contentEquals(ArtifactIdentity.file(collector)))
        val measuredFixture = modules.single { it.get("representativeClass").asString.contentEquals(ServerPerformanceBridge::class.java.name) }
        require(
            measuredFixture
                .getAsJsonObject("classTree")
                .get("sha256")
                .asString
                .contentEquals(fixtureHash),
        ) { "The server fixture classes differ from the evidence processor" }
        JvmPerformanceEvidence.verifyEqual(listOf(JsonObject().apply { add("runtime_identity", identity) }, report), setOf("runtime_identity"))
    }
}
