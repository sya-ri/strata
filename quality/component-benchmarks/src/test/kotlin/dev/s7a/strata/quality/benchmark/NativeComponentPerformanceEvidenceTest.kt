package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.s7a.strata.performance.PerformanceProfile
import dev.s7a.strata.performance.PerformanceSelection
import org.junit.jupiter.api.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Admission regressions reject missing native cases, incomplete presentation boundaries and leaked resources.
 * These detached contract inputs contain no timings and do not constitute collected performance evidence.
 */
internal class NativeComponentPerformanceEvidenceTest {
    @Test
    internal fun incompleteGpuPairsCannotCertifyCompletePresentation() {
        listOf("native_gpu", "presentation_gpu").forEach { scope ->
            listOf("duration", "operation_to_completion_observation").forEach { distribution ->
                val report = complete()
                val phase = report.getAsJsonArray("phases")[0].asJsonObject
                phase.add("native_gpu", completeGpu("GUI commands"))
                phase.add("presentation_gpu", completeGpu("Frame preparation through GUI consumption"))
                val gpu = phase.getAsJsonObject(scope)
                gpu.getAsJsonObject(distribution).addProperty("samples", 59)
                assertFails { NativeComponentPerformanceEvidence.verify(report) }
                gpu.getAsJsonObject(distribution).addProperty("samples", 60)
                NativeComponentPerformanceEvidence.verify(report)
                gpu.addProperty("timestamp_period_ns", 0.0)
                assertFails { NativeComponentPerformanceEvidence.verify(report) }
            }
        }
    }

    @Test
    internal fun availableGuiQueriesRequireIndependentFullPresentationPairs() {
        val report = complete()
        val phase = report.getAsJsonArray("phases")[0].asJsonObject
        val gui = completeGpu("GUI commands")
        phase.add("native_gpu", gui)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        phase.add("presentation_gpu", JsonNull.INSTANCE)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        phase.add("presentation_gpu", unavailableGpu())
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        phase.add("presentation_gpu", gui.deepCopy())
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        val full = completeGpu("Frame preparation through GUI consumption")
        phase.add("presentation_gpu", full)
        NativeComponentPerformanceEvidence.verify(report)
        full.remove("duration")
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
    }

    @Test
    internal fun fullPresentationAvailabilityMustMatchTheGuiScope() {
        val report = complete()
        val phase = report.getAsJsonArray("phases")[0].asJsonObject
        phase.add("presentation_gpu", completeGpu("Frame preparation through GUI consumption"))
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
    }

    @Test
    internal fun mixedFullScopeAncestorsCannotCertifyOneRawMatrix() {
        val legacy = complete()
        val declared = complete()
        declared.getAsJsonArray("phases").forEach { phase -> phase.asJsonObject.add("presentation_gpu", JsonNull.INSTANCE) }
        assertFails { NativeComponentPerformanceEvidence.presentationGpuMetricsPresent(listOf(legacy, declared)) }
        legacy.getAsJsonArray("phases")[0].asJsonObject.add("presentation_gpu", unavailableGpu())
        assertFails { NativeComponentPerformanceEvidence.presentationGpuMetricsPresent(listOf(legacy)) }
    }

    @Test
    internal fun legacyUnavailableQueriesKeepFullGpuMetricsUnavailable() {
        val report = complete()
        NativeComponentPerformanceEvidence.verify(report)
        assertFalse(NativeComponentPerformanceEvidence.presentationGpuMetricsPresent(listOf(report)))
        report.getAsJsonArray("phases").forEach { phase -> phase.asJsonObject.add("presentation_gpu", JsonNull.INSTANCE) }
        NativeComponentPerformanceEvidence.verify(report)
        assertTrue(NativeComponentPerformanceEvidence.presentationGpuMetricsPresent(listOf(report)))
        report.getAsJsonArray("phases").forEach { phase -> phase.asJsonObject.add("presentation_gpu", unavailableGpu()) }
        NativeComponentPerformanceEvidence.verify(report)
    }

    @Test
    internal fun sampledCorpusCannotCertifyCanonicalComponentCoverage() {
        val report = complete(sampledImages = true)
        val names = report.getAsJsonArray("phases").map { it.asJsonObject.get("case").asString }.toSet()
        NativeComponentPerformanceEvidence.verify(report, PerformanceSelection(names), sampledImages = true)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        assertFails { NativeComponentPerformanceEvidence.verify(report, PerformanceSelection(names)) }
    }

    @Test
    internal fun quickEvidenceHasItsOwnCountsScaleAndIdentity() {
        val report = complete()
        val selection = PerformanceSelection(report.getAsJsonArray("phases").map { it.asJsonObject.get("case").asString }.toSet(), "TextField")
        report.addProperty("workload_id", "native-components-selected-presented-v1-quick")
        report.addProperty("measurement_profile", "Quick")
        report.addProperty("warmup", 3)
        report.add("selected_cases", JsonArray().apply { add("TextField") })
        report.add(
            "phases",
            JsonArray().apply {
                report
                    .getAsJsonArray("phases")
                    .filter {
                        val phase = it.asJsonObject
                        phase.get("case").asString.contentEquals("TextField") && phase.get("gui_scale").asInt == 3
                    }.forEach {
                        val phase = it.deepCopy().asJsonObject
                        phase.addProperty("samples", 10)
                        phase.getAsJsonObject("frame_interval").addProperty("samples", 10)
                        phase.getAsJsonObject("native_counter_delta").addProperty("renderExtractionCount", 10)
                        add(phase)
                    }
            },
        )
        NativeComponentPerformanceEvidence.verify(report, selection, PerformanceProfile.Quick)
        assertFails { NativeComponentPerformanceEvidence.verify(report, selection) }
        val mutations: List<(JsonObject) -> Unit> =
            listOf(
                { it.addProperty("gui_scale", 1) },
                { it.addProperty("samples", 60) },
                { it.getAsJsonObject("frame_interval").addProperty("samples", 9) },
            )
        mutations.forEach { mutate ->
            val changed = report.deepCopy()
            mutate(changed.getAsJsonArray("phases")[0].asJsonObject)
            assertFails { NativeComponentPerformanceEvidence.verify(changed, selection, PerformanceProfile.Quick) }
        }
    }

    @Test
    internal fun missingScaleCannotProduceACompleteMatrix() {
        val report = complete()
        NativeComponentPerformanceEvidence.verify(report)
        report.getAsJsonArray("phases").remove(0)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
    }

    @Test
    internal fun extractionAndCompleteFrameCountsMustBothMatch() {
        listOf("frame_interval", "native_counter_delta").forEach { field ->
            val report = complete()
            val key = if (field.contentEquals("frame_interval")) "samples" else "renderExtractionCount"
            report
                .getAsJsonArray("phases")[0]
                .asJsonObject
                .getAsJsonObject(field)
                .addProperty(key, 59)
            assertFails { NativeComponentPerformanceEvidence.verify(report) }
        }
    }

    @Test
    internal fun outstandingNativeLeasesRejectSuccessfulStatus() {
        val report = complete()
        report.getAsJsonObject("native_resource_release").addProperty("leases_closed", 3)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
    }

    @Test
    internal fun targetedEvidenceRequiresTheSameExplicitSelection() {
        val report = complete()
        val phases = report.getAsJsonArray("phases")
        val names = phases.map { it.asJsonObject.get("case").asString }.toSet()
        val selection = PerformanceSelection(names, "TextField")
        report.add(
            "phases",
            JsonArray().apply {
                phases
                    .filter {
                        it.asJsonObject
                            .get("case")
                            .asString
                            .contentEquals("TextField")
                    }.forEach(::add)
            },
        )
        report.addProperty("workload_id", "native-components-selected-presented-v1")
        report.add("selected_cases", JsonArray().apply { add("TextField") })
        report.getAsJsonObject("native_resource_release").apply {
            listOf("leases", "renderers").forEach { kind ->
                addProperty("${kind}_opened", 0)
                addProperty("${kind}_closed", 0)
            }
        }
        NativeComponentPerformanceEvidence.verify(report, selection)
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
        assertFails { NativeComponentPerformanceEvidence.verify(report, PerformanceSelection(names, "TextArea")) }
        report.getAsJsonArray("phases").remove(0)
        assertFails { NativeComponentPerformanceEvidence.verify(report, selection) }
    }

    @Test
    internal fun duplicatedNativeIntervalsCannotPassSetValidation() {
        val report = complete()
        report.getAsJsonArray("phases").add(report.getAsJsonArray("phases")[0].deepCopy())
        assertFails { NativeComponentPerformanceEvidence.verify(report) }
    }

    private fun complete(sampledImages: Boolean = false): JsonObject =
        JsonObject().apply {
            addProperty("workload_id", if (sampledImages) "native-sampled-images-presented-v1" else "native-components-presented-v1")
            addProperty("status", "passed")
            addProperty("framebuffer_width", 1920)
            addProperty("framebuffer_height", 1080)
            addProperty("vsync", false)
            addProperty("framerate_limit", 120)
            addProperty("warmup", 30)
            addProperty("settle_frames", 8)
            addProperty("preparation_timeout_ms", 120_000)
            val rows = checkNotNull(javaClass.getResourceAsStream(if (sampledImages) "/native-sampled-images.tsv" else "/native-components.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            add(
                "phases",
                JsonArray().apply {
                    rows.forEach { name ->
                        (1..4).forEach { scale ->
                            add(
                                JsonObject().apply {
                                    addProperty("case", name)
                                    addProperty("gui_scale", scale)
                                    addProperty("operation", "presented")
                                    addProperty("samples", 60)
                                    add("native_gpu", unavailableGpu())
                                    add("frame_interval", JsonObject().apply { addProperty("samples", 60) })
                                    add("native_counter_delta", JsonObject().apply { addProperty("renderExtractionCount", 60) })
                                    add("diagnostics", JsonObject().apply { addProperty("node_inventory_truncated", false) })
                                },
                            )
                        }
                    }
                },
            )
            add(
                "native_resource_release",
                JsonObject().apply {
                    listOf("leases", "renderers").forEach { kind ->
                        addProperty("${kind}_opened", 4)
                        addProperty("${kind}_closed", 4)
                    }
                },
            )
        }

    private fun completeGpu(scope: String): JsonObject =
        JsonObject().apply {
            addProperty("available", true)
            addProperty("samples", 60)
            addProperty("timestamp_period_ns", 1.0)
            addProperty("scope", scope)
            add("duration", JsonObject().apply { addProperty("samples", 60) })
            add("operation_to_completion_observation", JsonObject().apply { addProperty("samples", 60) })
        }

    private fun unavailableGpu(): JsonObject =
        JsonObject().apply {
            addProperty("available", false)
            addProperty("reason", "No native device in detached contract inputs")
            add("duration", JsonNull.INSTANCE)
            add("operation_to_completion_observation", JsonNull.INSTANCE)
        }
}
