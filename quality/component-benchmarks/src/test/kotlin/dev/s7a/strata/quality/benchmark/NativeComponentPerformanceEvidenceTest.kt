package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.PerformanceSelection
import org.junit.jupiter.api.Test
import kotlin.test.assertFails

/**
 * Admission regressions reject missing native cases, incomplete presentation boundaries and leaked resources.
 * These detached contract inputs contain no timings and do not constitute collected performance evidence.
 */
internal class NativeComponentPerformanceEvidenceTest {
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

    private fun complete(): JsonObject =
        JsonObject().apply {
            addProperty("workload_id", "native-components-presented-v1")
            addProperty("status", "passed")
            addProperty("framebuffer_width", 1920)
            addProperty("framebuffer_height", 1080)
            addProperty("vsync", false)
            addProperty("framerate_limit", 120)
            addProperty("warmup", 30)
            addProperty("settle_frames", 8)
            addProperty("preparation_timeout_ms", 120_000)
            val rows = checkNotNull(javaClass.getResourceAsStream("/native-components.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }
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
}
