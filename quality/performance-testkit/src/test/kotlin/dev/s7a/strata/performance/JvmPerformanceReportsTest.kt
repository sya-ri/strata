package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Verifies shared phase aggregation and immutable evidence constraints without measuring invented fixtures.
 */
class JvmPerformanceReportsTest {
    @field:TempDir lateinit var directory: Path

    @Test
    fun actualPublishedCollectorContainsNoPythonCompatibilityResources() {
        val collector = Path.of(requireNotNull(System.getProperty("strata.testkit.jar")))
        ZipFile(collector.toFile()).use { archive ->
            assertTrue(archive.entries().asSequence().none { it.name.endsWith(".py") || it.name.endsWith(".pyc") })
        }
    }

    @Test
    fun summarizesPerRunQuantilesAndKeepsUnavailableMeasurementsNull() {
        PackagedEvidenceFixture().use { kit ->
            val fixture = ReportEvidenceFixtureData(directory, kit)
            val runs = fixture.create()
            val summary = fixture.summarize(runs)
            val metrics =
                summary
                    .arrayField("phases")
                    .single()
                    .asJsonObject
                    .objectField("metrics")
            assertEquals(2.0, metrics.get("p50_ms").asDouble)
            assertTrue(metrics.get("allocation").isJsonNull)
            assertEquals(ArtifactIdentity.file(runs[0]), summary.arrayField("sources")[0].asJsonObject.textField("sha256"))
            assertFails { fixture.summarize(runs) { error("Actual work did not occur") } }
        }
    }

    @Test
    fun changedInputsCollectorsIdentitiesMatricesAndAssertionsCannotCertifySuccess() {
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.remove("collector_identity") },
                { it.objectField("collector_identity").addProperty("code_source_sha256", "0".repeat(64)) },
                { it.remove("java") },
                { it.addProperty("java", "changed") },
                { it.addProperty("run_id", "invalid") },
                { it.addProperty("status", "failed") },
                { it.addProperty("schema_version", true) },
                { it.addProperty("workload_id", "other") },
                { it.add("phases", JsonArray()) },
                { it.arrayField("phases").add(it.arrayField("phases")[0].deepCopy()) },
                { it.arrayField("phases")[0].asJsonObject.remove("case") },
                { it.arrayField("phases")[0].asJsonObject.addProperty("semantics", "changed") },
                { it.arrayField("phases")[0].asJsonObject.addProperty("commands", 11) },
            )
        PackagedEvidenceFixture().use { kit ->
            mutations.forEachIndexed { index, mutate ->
                val fixture = ReportEvidenceFixtureData(directory.resolve("fault-$index"), kit)
                val paths = fixture.create()
                fixture.mutate(paths[1], mutate)
                assertFails("Mutation $index") { fixture.summarize(paths) }
            }
            val fixture = ReportEvidenceFixtureData(directory.resolve("duplicate"), kit)
            val paths = fixture.create()
            assertFails { fixture.summarize(listOf(paths[0], paths[0], paths[2])) }
            assertFails { fixture.summarize(paths.take(2)) }
        }
    }

    @Test
    fun comparesVariantBytesButRejectsReusedRunsAndChangedControlledWork() {
        PackagedEvidenceFixture().use { kit ->
            val before = ReportEvidenceFixtureData(directory.resolve("before"), kit)
            val after = ReportEvidenceFixtureData(directory.resolve("after"), kit)
            val baseline = before.create()
            val candidate = after.create()
            candidate.forEach { path ->
                after.mutate(path) {
                    it.addProperty("runtime", "candidate")
                    it.arrayField("phases")[0].asJsonObject.addProperty("commands", 1)
                }
            }
            assertEquals(1, before.compare(baseline, candidate).arrayField("phases").size())
            assertFails { before.compare(baseline, baseline) }
            candidate.forEach { path -> after.mutate(path) { it.addProperty("input", "changed") } }
            assertFails { before.compare(baseline, candidate) }
        }
    }

    @Test
    fun negativeNonNumericMissingAndZeroDivisorMetricsFail() {
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.remove("wall_p50_ns") },
                { it.addProperty("wall_p50_ns", -1) },
                { it.addProperty("wall_p50_ns", true) },
                { it.addProperty("wall_p50_ns", "1") },
                { it.addProperty("wall_p50_ns", Double.NaN) },
                { it.addProperty("allocation", -1) },
            )
        PackagedEvidenceFixture().use { kit ->
            mutations.forEachIndexed { index, mutate ->
                val fixture = ReportEvidenceFixtureData(directory.resolve("metric-$index"), kit)
                val paths = fixture.create()
                fixture.mutate(paths[1]) { mutate(it.arrayField("phases")[0].asJsonObject) }
                assertFails { fixture.summarize(paths) }
            }
            val fixture = ReportEvidenceFixtureData(directory.resolve("divisor"), kit)
            val paths = fixture.create()
            paths.forEach { path ->
                fixture.mutate(path) {
                    it.arrayField("phases")[0].asJsonObject.apply {
                        addProperty("samples", 0)
                        addProperty("allocation", 100)
                    }
                }
            }
            assertFails { fixture.summarize(paths) }
        }
    }

    @Test
    fun completeImageInventoriesMustMatchActualBytes() {
        val before = Files.createDirectories(directory.resolve("before"))
        val after = Files.createDirectories(directory.resolve("after"))
        Files.writeString(before.resolve("home.png"), "same pixels")
        Files.writeString(after.resolve("home.png"), "same pixels")
        assertEquals(1, JvmPerformanceReports.compareImages(before, after, 1).size())
        assertFails { JvmPerformanceReports.compareImages(before, after, 2) }
        Files.writeString(after.resolve("home.png"), "changed pixels")
        assertFails { JvmPerformanceReports.compareImages(before, after, 1) }
    }

    @Test
    fun replacedCollectorCannotCertifyAnotherIdentityAfterLoading() {
        val selected = Files.copy(Path.of(requireNotNull(System.getProperty("strata.testkit.jar"))), directory.resolve("selected.jar"))
        PackagedEvidenceFixture(selected).use { kit ->
            val fixture = ReportEvidenceFixtureData(directory.resolve("reports"), kit)
            val reports = fixture.create()
            fixture.summarize(reports)
            Files.writeString(selected, "changed after load", StandardOpenOption.APPEND)
            reports.forEach { path -> fixture.mutate(path) { it.objectField("collector_identity").addProperty("code_source_sha256", ArtifactIdentity.file(selected)) } }
            assertFails { fixture.summarize(reports) }
        }
    }

    @Test
    fun missingConditionsDuplicatePhasesAndInvalidMetricsFailSharedPrimitives() {
        assertFails { JvmPerformanceEvidence.verifyEqual(listOf(JsonObject(), JsonObject()), setOf("java")) }
        val phase = JsonObject().apply { addProperty("case", "home") }
        val report =
            JsonObject().apply {
                add(
                    "phases",
                    JsonArray().apply {
                        add(phase)
                        add(phase.deepCopy())
                    },
                )
            }
        assertFails { JvmPerformanceEvidence.indexPhases(report, listOf("case")) }
        listOf(emptyList(), listOf(Double.NaN), listOf(Double.POSITIVE_INFINITY), listOf(-1.0)).forEach { values -> assertFails { JvmPerformanceEvidence.median(values) } }
        assertEquals(20.0, JvmPerformanceEvidence.median(listOf(10.0, 30.0, 20.0)))
        assertEquals(Double.MAX_VALUE, JvmPerformanceEvidence.median(listOf(Double.MAX_VALUE, Double.MAX_VALUE)))
        val detached = JvmPerformanceEvidence.projectContract(phase, setOf("case"))
        assertEquals(JsonObject(), detached)
        assertTrue(phase.has("case"))
        val metric = JsonObject().apply { add("unavailable", JsonNull.INSTANCE) }
        assertTrue(
            JvmPerformanceEvidence
                .projectContract(metric, emptySet())
                .asJsonObject
                .get("unavailable")
                .isJsonNull,
        )
    }
}
