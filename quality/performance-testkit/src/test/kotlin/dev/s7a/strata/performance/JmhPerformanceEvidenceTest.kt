package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Preserves the formal JMH evidence rejection contracts while removing the Python runtime.
 */
class JmhPerformanceEvidenceTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun realArchivesAndIndependentMeansPreserveUnavailableGcAndSamplePercentiles() {
        PackagedEvidenceFixture().use { kit ->
            val average = JmhEvidenceFixtureData(directory.resolve("average"), kit.archive).create()
            val report = kit.summarize(average)
            assertEquals(1, report.countField("case_count"))
            val metric = report.arrayField("cases").single().asJsonObject
            assertEquals(2.0, metric.get("primary_score_median").asDouble)
            assertTrue(metric.get("gc_time_ms_median_available").isJsonNull)
            assertFalse(metric.has("per_run_percentile_medians"))
            val sampled = JmhEvidenceFixtureData(directory.resolve("sample"), kit.archive).create(sample = true)
            val percentiles =
                kit
                    .summarize(sampled)
                    .arrayField("cases")
                    .single()
                    .asJsonObject
                    .objectField("per_run_percentile_medians")
            assertEquals(JsonParser.parseString("""{"50.0":1,"95.0":2,"99.0":3}"""), percentiles)
        }
    }

    @Test
    fun incompleteForkConfirmationsMatricesMetricsAndConditionsRejectSuccess() {
        val mutations = listOf(JmhEvidenceMutation.MissingMarker, JmhEvidenceMutation.MissingConfirmation, JmhEvidenceMutation.InvalidConfirmationScore, JmhEvidenceMutation.IncompleteConfirmation, JmhEvidenceMutation.BooleanConfirmation, JmhEvidenceMutation.MissingWorkload, JmhEvidenceMutation.MissingMetric, JmhEvidenceMutation.MissingHistogram, JmhEvidenceMutation.DifferentEnvironment, JmhEvidenceMutation.BooleanIndex, JmhEvidenceMutation.DuplicateRun)
        PackagedEvidenceFixture().use { kit ->
            mutations.forEach { name ->
                val fixture = JmhEvidenceFixtureData(directory.resolve(name.name), kit.archive)
                val runs = fixture.create(sample = name == JmhEvidenceMutation.MissingHistogram)
                val selected = runs[1]
                when (name) {
                    JmhEvidenceMutation.MissingMarker -> fixture.mutateReceipt(selected) { it.remove("fork_verification") }
                    JmhEvidenceMutation.DifferentEnvironment -> fixture.mutateReceipt(selected) { it.objectField("environment").addProperty("java", "changed") }
                    JmhEvidenceMutation.BooleanIndex -> fixture.mutateReceipt(selected) { it.addProperty("repetition", true) }
                    JmhEvidenceMutation.DuplicateRun -> fixture.mutateReceipt(selected) { it.add("run_id", JvmEvidenceFiles.document(runs[0].resolve("receipt.json")).get("run_id")) }
                    else -> fixture.mutateRaw(selected) { row -> mutateMetric(row, name) }
                }
                assertFails(name.name) { kit.summarize(runs) }
            }
        }
    }

    @Test
    fun rawDigestsCollectorsTargetsAndUnsafeInputsRejectSuccess() {
        val mutations = listOf(JmhEvidenceMutation.AlteredRaw, JmhEvidenceMutation.AlteredCollector, JmhEvidenceMutation.AlteredTarget, JmhEvidenceMutation.InputMissing, JmhEvidenceMutation.InputChanged, JmhEvidenceMutation.InputUnsafe, JmhEvidenceMutation.InputDuplicate)
        PackagedEvidenceFixture().use { kit ->
            mutations.forEach { name ->
                val fixture = JmhEvidenceFixtureData(directory.resolve(name.name), kit.archive)
                val runs = fixture.create()
                val selected = runs[1]
                when (name) {
                    JmhEvidenceMutation.AlteredRaw -> Files.writeString(selected.resolve("results.json"), "changed")
                    JmhEvidenceMutation.AlteredCollector -> Files.writeString(selected.resolve("collector.jar"), "changed")
                    JmhEvidenceMutation.AlteredTarget -> Files.writeString(selected.resolve("target-0.jar"), "changed")
                    JmhEvidenceMutation.InputMissing -> Files.delete(selected.resolve("input-0.bin"))
                    JmhEvidenceMutation.InputChanged -> Files.writeString(selected.resolve("input-0.bin"), "changed")
                    JmhEvidenceMutation.InputUnsafe -> fixture.mutateReceipt(selected) { it.objectField("inputs").objectField("font").addProperty("archive", "../input-0.bin") }
                    JmhEvidenceMutation.InputDuplicate -> fixture.mutateReceipt(selected) { it.objectField("inputs").add("duplicate", it.objectField("inputs").get("font").deepCopy()) }
                    else -> error("Unexpected archive mutation: $name")
                }
                assertFails(name.name) { kit.summarize(runs) }
            }
        }
    }

    @Test
    fun comparisonRevalidatesBothSidesAndNeverTurnsMissingOrZeroRatiosIntoNumbers() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = JmhEvidenceFixtureData(directory.resolve("baseline"), kit.archive).create()
            val candidate = JmhEvidenceFixtureData(directory.resolve("candidate"), kit.archive).create()
            val modifier = JmhEvidenceFixtureData(directory, kit.archive)
            candidate.forEach { run ->
                EvidenceArchiveFixture.append(run.resolve("target-0.jar"))
                modifier.mutateReceipt(run) { receipt ->
                    receipt
                        .objectField("runtime_metadata")
                        .arrayField("modules")[0]
                        .asJsonObject
                        .objectField("codeSource")
                        .addProperty("sha256", ArtifactIdentity.file(run.resolve("target-0.jar")))
                }
            }
            val report = kit.compare(baseline, candidate)
            assertEquals(listOf("fixture"), report.arrayField("changed_targets").map { it.asString })
            val metric =
                report
                    .arrayField("cases")
                    .single()
                    .asJsonObject
                    .objectField("metrics")
            assertEquals(0.0, metric.objectField("primary_score_median").get("delta").asDouble)
            assertTrue(metric.objectField("gc_count_median").get("candidate_to_baseline").isJsonNull)
            assertTrue(metric.objectField("gc_time_ms_median_available").get("delta").isJsonNull)
            assertFails { kit.compare(baseline, baseline) }
        }
    }

    private fun mutateMetric(
        row: JsonObject,
        name: JmhEvidenceMutation,
    ) {
        val secondary = row.objectField("secondaryMetrics")
        when (name) {
            JmhEvidenceMutation.MissingConfirmation -> secondary.remove("strata.provenance")
            JmhEvidenceMutation.InvalidConfirmationScore -> secondary.objectField("strata.provenance").addProperty("score", 0)
            JmhEvidenceMutation.IncompleteConfirmation -> secondary.objectField("strata.provenance").add("rawData", JsonParser.parseString("[[]]"))
            JmhEvidenceMutation.BooleanConfirmation -> secondary.objectField("strata.provenance").add("rawData", JsonParser.parseString("[[true]]"))
            JmhEvidenceMutation.MissingWorkload -> row.addProperty("benchmark", "fixture.Frame.missing")
            JmhEvidenceMutation.MissingMetric -> secondary.remove("gc.alloc.rate.norm")
            JmhEvidenceMutation.MissingHistogram -> row.objectField("primaryMetric").add("rawDataHistogram", JsonArray())
            else -> error("Unexpected metric mutation: $name")
        }
    }

    @Test
    fun changedInputsFixturesHarnessOptionsUnitsAndControlsCannotEnterAComparison() {
        PackagedEvidenceFixture().use { kit ->
            listOf(JmhEvidenceMutation.ChangedInput, JmhEvidenceMutation.ChangedFixture, JmhEvidenceMutation.ChangedHarness, JmhEvidenceMutation.ChangedEnvironment, JmhEvidenceMutation.ChangedOptions, JmhEvidenceMutation.ChangedUnit, JmhEvidenceMutation.ChangedControl).forEach { name ->
                val baseline = JmhEvidenceFixtureData(directory.resolve("${name.name}-before"), kit.archive).create()
                val changed = JmhEvidenceFixtureData(directory.resolve("${name.name}-after"), kit.archive)
                val candidate = changed.create()
                candidate.forEach { run ->
                    when (name) {
                        JmhEvidenceMutation.ChangedInput -> {
                            Files.writeString(run.resolve("input-0.bin"), "changed input")
                            changed.mutateReceipt(run) { it.objectField("inputs").objectField("font").addProperty("sha256", ArtifactIdentity.file(run.resolve("input-0.bin"))) }
                        }

                        JmhEvidenceMutation.ChangedFixture -> {
                            changed.mutateReceipt(run) { it.objectField("fixture_identity").addProperty("fixture.Frame", "2".repeat(64)) }
                        }

                        JmhEvidenceMutation.ChangedHarness -> {
                            EvidenceArchiveFixture.append(run.resolve("harness.jar"))
                            changed.mutateReceipt(run) { it.addProperty("harness_sha256", ArtifactIdentity.file(run.resolve("harness.jar"))) }
                        }

                        JmhEvidenceMutation.ChangedEnvironment -> {
                            changed.mutateReceipt(run) { it.objectField("environment").addProperty("java", "changed") }
                        }

                        JmhEvidenceMutation.ChangedOptions -> {
                            changed.mutateReceipt(run) { it.arrayField("arguments").add("changed") }
                        }

                        JmhEvidenceMutation.ChangedUnit -> {
                            changed.mutateRaw(run) { it.objectField("primaryMetric").addProperty("scoreUnit", "ms/op") }
                        }

                        JmhEvidenceMutation.ChangedControl -> {
                            changed.mutateRaw(run) { it.addProperty("measurementTime", "2 s") }
                        }

                        else -> {
                            error("Unexpected comparison mutation: $name")
                        }
                    }
                }
                assertFails(name.name) { kit.compare(baseline, candidate) }
            }
        }
    }
}
