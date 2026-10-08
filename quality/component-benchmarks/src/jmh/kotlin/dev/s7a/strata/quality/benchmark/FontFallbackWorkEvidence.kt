package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Complete generated-case, glyph-bit, pixel and ownership evidence collected outside all measured operations.
 */
public object FontFallbackWorkEvidence {
    /**
     * With one argument writes the separate atlas-rejection PNG input; otherwise verifies all 48 cases.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size <= 1)
        if (args.isNotEmpty()) {
            val file = Path.of(args.single())
            check(Files.exists(file).not())
            Files.write(file, FontRasterAssets.png(257))
            return
        }
        verify()
    }

    /**
     * Requires cached/uncached complete glyph parity and publishes actual input and output fingerprints.
     */
    public fun verify() {
        check(JmhWorkloadInventory.capture(listOf(FontFallbackBenchmark::class.java), setOf("avgt")).size == 48)
        val cases = JsonArray()
        FontFallbackWorkload.entries.forEach { workload ->
            listOf(1, 10).forEach { depth -> verifySession(workload, depth).forEach(cases::add) }
        }
        check(cases.size() == 48)
        println(
            JsonObject().apply {
                addProperty("contract", "font-fallback-parity-v1")
                addProperty("status", "passed")
                addProperty("measured", false)
                addProperty("case_count", cases.size())
                add(
                    "untimed_work_counter_names",
                    JsonArray().apply {
                        listOf("backend_open_attempts", "png_decode_calls", "face_open_attempts", "native_glyph_calls", "face_close_calls", "backend_close_calls").forEach { add(it) }
                    },
                )
                addProperty("cc0_font_sha256", FontRasterAssets.digest(Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))))
                addProperty("atlas_rejection_png_sha256", FontRasterAssets.digest(FontRasterAssets.png(257)))
                add("cases", cases)
            },
        )
    }

    private fun verifySession(
        workload: FontFallbackWorkload,
        depth: Int,
    ): List<JsonObject> {
        val owner =
            FontFallbackBenchmark.FallbackSession().apply {
                this.workload = workload
                this.depth = depth
            }
        owner.setup()
        val records =
            try {
                val warm = owner.warm()
                equalGlyph(warm, owner.uncachedWarm())
                val traces = owner.traces()
                check(traces.size == 3 && traces.all { it.size == 64 })
                for (reference in traces.drop(1)) traces.first().zip(reference).forEach { (cached, uncached) -> equalGlyph(cached, uncached) }
                equalGlyph(warm, traces.first().first())
                val churn = owner.churn()
                equalGlyph(churn, traces.first().last())
                equalGlyph(churn, owner.uncached())
                equalGlyph(warm, owner.warm())
                val lifetime = owner.lifecycle()
                equalGlyph(churn, lifetime)
                val retained = owner.retained
                check(retained[0] <= 64L && retained[1] <= 1024 * 1024L && retained[2] <= 1L)
                if (workload in setOf(FontFallbackWorkload.StbLate, FontFallbackWorkload.FreeTypeLate)) check(warm.image != null)
                if (workload in setOf(FontFallbackWorkload.Missing, FontFallbackWorkload.Poisoned, FontFallbackWorkload.AtlasRejected)) check(warm.advance == 6f)
                if (workload in setOf(FontFallbackWorkload.First, FontFallbackWorkload.Late, FontFallbackWorkload.FilteredLate)) check(warm.advance == 7f && warm.image == null)
                listOf(
                    record(workload, depth, "warm", warm, listOf(warm)),
                    record(workload, depth, "churn", churn, traces.first()),
                    record(workload, depth, "lifecycle", lifetime, traces.last()),
                )
            } finally {
                owner.close()
            }
        val work = owner.observedWork
        check(work[0] == work[5]) { "A fixture engine retained its native backend after close" }
        return records.onEach { row -> row.add("untimed_session_observed_work", JsonArray().apply { work.forEach { add(it) } }) }
    }

    private fun equalGlyph(
        first: MinecraftFontGlyph,
        second: MinecraftFontGlyph,
    ) {
        check(first.copy(image = null) == second.copy(image = null))
        check(first.image?.size == second.image?.size && (first.image?.copyArgb()?.contentEquals(second.image?.copyArgb()) ?: (second.image == null)))
    }

    private fun record(
        workload: FontFallbackWorkload,
        depth: Int,
        operation: String,
        glyph: MinecraftFontGlyph,
        trace: List<MinecraftFontGlyph>,
    ): JsonObject =
        glyphRecord(glyph).apply {
            addProperty("workload", workload.name)
            addProperty("depth", depth)
            addProperty("operation", operation)
            add(
                "glyph_trace",
                JsonArray().apply {
                    trace.forEachIndexed { index, scalarGlyph -> add(glyphRecord(scalarGlyph).apply { addProperty("scalar", 65 + index) }) }
                },
            )
        }

    private fun glyphRecord(glyph: MinecraftFontGlyph): JsonObject =
        JsonObject().apply {
            addProperty("advance_bits", glyph.advance.toBits())
            add("metric_bits", JsonArray().apply { listOf(glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset).forEach { add(it.toBits()) } })
            addProperty("orientation", glyph.orientation.name)
            addProperty("channel", glyph.channel.name)
            addProperty("oversized_raster_width", glyph.oversizedRasterSize?.width)
            addProperty("oversized_raster_height", glyph.oversizedRasterSize?.height)
            val image = glyph.image
            addProperty("width", image?.size?.width)
            addProperty("height", image?.size?.height)
            image?.let {
                val bytes = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(it.size.width, it.size.height), 4))
                it.copyArgb().forEach(bytes::putInt)
                addProperty("argb_sha256", FontRasterAssets.digest(bytes.array()))
            }
        }
}
