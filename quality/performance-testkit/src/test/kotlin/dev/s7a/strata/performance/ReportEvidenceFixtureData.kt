package dev.s7a.strata.performance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Builds detached synthetic phase reports and invokes the verifier from the actual testkit archive.
 */
internal class ReportEvidenceFixtureData(
    private val root: Path,
    private val kit: PackagedEvidenceFixture,
) {
    private val contractType = kit.type("PerformanceReportContract")
    private val metricType = kit.type("PerformanceReportMetric")
    private val contract =
        contractType.constructors.single { it.parameterCount == 9 }.newInstance(
            "fixture-phases-v1",
            listOf("case", "operation", "viewport"),
            1,
            setOf("java", "input"),
            setOf("samples", "semantics"),
            setOf("runtime"),
            setOf("commands"),
            3,
            1,
        )
    private val metric = metricType.constructors.single { it.parameterCount == 4 }.newInstance("p50_ms", listOf("wall_p50_ns"), null, 1e-6)
    private val allocation = metricType.constructors.single { it.parameterCount == 4 }.newInstance("allocation", listOf("allocation"), listOf("samples"), 1.0)

    /**
     * Creates independently identified receipts with the actual packaged collector identity.
     */
    fun create(): List<Path> =
        (1..3).map { number ->
            Files.createDirectories(root)
            val report = JsonParser.parseString("""{"status":"passed","schema_version":1,"workload_id":"fixture-phases-v1","java":"25","input":"fixed","runtime":"baseline","phases":[{"case":"home","operation":"static","viewport":{"width":640},"samples":60,"semantics":"same","commands":10,"wall_p50_ns":${number * 1000000},"allocation":null}]}""").asJsonObject
            report.addProperty("run_id", UUID.randomUUID().toString())
            report.add(
                "collector_identity",
                JsonObject().apply {
                    addProperty("contract", "strata-performance-testkit-v1")
                    addProperty("code_source_sha256", ArtifactIdentity.file(kit.archive))
                },
            )
            Files.writeString(root.resolve("run-$number.json"), report.toString())
        }

    /**
     * Changes a raw report without bypassing the packaged verifier.
     */
    fun mutate(
        path: Path,
        change: (JsonObject) -> Unit,
    ) {
        val report = JvmEvidenceFiles.document(path)
        change(report)
        Files.writeString(path, report.toString())
    }

    /**
     * Summarizes from the actual archive, preserving application assertion failures.
     */
    fun summarize(
        paths: List<Path>,
        validate: (JsonObject) -> Unit = {},
    ): JsonObject =
        kit.invoke(
            "JvmPerformanceReports",
            "summarize",
            arrayOf(List::class.java, Path::class.java, contractType, List::class.java, Function1::class.java),
            paths,
            kit.archive,
            contract,
            listOf(metric, allocation),
            validate,
        ) as JsonObject

    /**
     * Compares independent raw reports through the common implementation.
     */
    fun compare(
        baseline: List<Path>,
        candidate: List<Path>,
    ): JsonObject =
        kit.invoke(
            "JvmPerformanceReports",
            "compare",
            arrayOf(List::class.java, List::class.java, Path::class.java, contractType, List::class.java, Function1::class.java),
            baseline,
            candidate,
            kit.archive,
            contract,
            listOf(metric, allocation),
            { _: JsonObject -> },
        ) as JsonObject
}
