package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmPerformanceMeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Checks that shared lifecycle processing preserves actual meter rows and rejects incomplete projections.
 */
internal class IndexedFontCpuComparisonTest {
    @Test
    fun detachedProjectionPreservesEveryOriginalLifetimeCounter() {
        val original = report()
        val snapshot = original.deepCopy()
        val source = JsonObject().apply { addProperty("sha256", "1".repeat(64)) }
        val projected = IndexedFontCpuComparison.lifecycleProjection(original, source)
        assertEquals(snapshot, original)
        assertEquals(original.get("setup_teardown"), projected.get("phases"))
        assertEquals(source, projected.get("lifecycle_projection_source"))
        projected.getAsJsonArray("phases").get(0).asJsonObject.addProperty("wall_total_ns", -1)
        assertEquals(snapshot, original)
    }

    @Test
    fun missingDuplicateAndChangedSampleBoundariesRejectProjection() {
        val missing = report().apply { getAsJsonArray("setup_teardown").remove(31) }
        assertThrows(IllegalArgumentException::class.java) { IndexedFontCpuComparison.lifecycleProjection(missing, JsonObject()) }
        val duplicate = report().apply { getAsJsonArray("setup_teardown").set(31, getAsJsonArray("setup_teardown").get(0).deepCopy()) }
        assertThrows(IllegalArgumentException::class.java) { IndexedFontCpuComparison.lifecycleProjection(duplicate, JsonObject()) }
        val changed = report().apply { getAsJsonArray("setup_teardown").get(0).asJsonObject.addProperty("samples", 2) }
        assertThrows(IllegalStateException::class.java) { IndexedFontCpuComparison.lifecycleProjection(changed, JsonObject()) }
    }

    private fun report(): JsonObject =
        JsonObject().apply {
            add(
                "setup_teardown",
                JsonArray().apply {
                    repeat(32) { index ->
                        val meter = JvmPerformanceMeter("lifetime-$index", 1)
                        meter.begin()
                        meter.end()
                        add(meter.result().apply { addProperty("warmup", 0) })
                    }
                },
            )
        }
}
