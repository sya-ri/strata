package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.Field
import java.nio.ByteBuffer

/**
 * All-case independent original ordered selection, expected pixels/metrics, clean/dirty work and ownership.
 * Logs construction retention separately from predicate counts; source-derived probe bounds are explicitly labelled.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object UnihexOverrideWorkEvidence {
    /**
     * Reconciles every registered workload before emitting an untimed admission document.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        verify()
    }

    /**
     * Checks every original input, complete scalar trace and operation outside the timed JMH boundary.
     */
    public fun verify() {
        check(JmhWorkloadInventory.capture(listOf(UnihexOverrideBenchmark::class.java), setOf("avgt")).size == 144)
        val records = JsonArray()
        UnihexOverrideScenario.entries.forEach { scenario -> verifyScenario(scenario).forEach(records::add) }
        check(records.size() == 144)
        println(
            JsonObject().apply {
                addProperty("contract", "unihex-override-parity-v1")
                addProperty("status", "passed")
                addProperty("measured", false)
                addProperty("case_count", records.size())
                addProperty("zip_sha256", FontRasterAssets.digest(UnihexOverrideAssets.bytes()))
                addProperty("native_presentation", "N/A: public JVM operations")
                add("cases", records)
            },
        )
    }

    private fun verifyScenario(scenario: UnihexOverrideScenario): List<JsonObject> {
        val state = UnihexOverrideBenchmark.OverrideSession().apply { this.scenario = scenario }
        state.setup()
        val records =
            try {
                val evidence = verifyTrace(scenario, state)
                val document = evidence.document
                val trace = evidence.trace
                val scalars = evidence.scalars
                val predicates = evidence.predicates
                equalGlyph(state.warm(), trace.first())
                equalGlyph(state.glyphs(), trace.last())
                equalGlyph(state.firstUse(), trace.first())
                equalGlyph(state.lifecycle(), trace.last())
                check(state.load().diagnostics.isEmpty())
                state.monitorWork()
                val clean = state.cleanFrame()
                check(state.cleanFrame() === clean)
                WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 2L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L)).verify(PerformanceJson.work(state.diagnostics))
                val cleanWork = state.diagnostics.deepCopy()
                val dirty = state.dirtyFrame()
                WorkExpectation(minimum = mapOf(UiRenderMetric.ObserveEvaluation.name to 1L, UiRenderMetric.Paint.name to 1L)).verify(PerformanceJson.work(state.diagnostics))
                val dirtyWork = state.diagnostics.deepCopy()
                val lifetime = state.textLifecycle()
                val replacement = state.replaceSnapshot()
                check(framePixels(lifetime) == framePixels(dirty))
                check(framePixels(replacement) == framePixels(dirty))
                check(state.subscriptions == 1)
                check(state.retained[0] <= scenario.cacheEntries && state.retained[1] <= 1024 * 1024 && state.retained[2] == 0L)
                val retention = indexRetention(state)
                listOf("glyphs", "warm", "firstUse", "load", "lifecycle", "cleanFrame", "dirtyFrame", "textLifecycle", "replaceSnapshot").map { operation ->
                    JsonObject().apply {
                        addProperty("scenario", scenario.name)
                        addProperty("operation", operation)
                        addProperty("range_count", scenario.ranges)
                        addProperty("cache_entries", scenario.cacheEntries)
                        addProperty("queries_per_batch", scenario.queries)
                        addProperty("document_sha256", FontRasterAssets.digest(document))
                        addProperty("original_batch_predicates", predicates)
                        add("actual_prepared_index_retention", retention)
                        add("glyph_trace", JsonArray().apply { trace.zip(scalars).forEach { (glyph, scalar) -> add(glyphRecord(glyph).apply { addProperty("scalar", scalar) }) } })
                        addProperty("clean_frame_argb_sha256", framePixels(clean))
                        addProperty("dirty_frame_argb_sha256", framePixels(dirty))
                        add("clean_work", cleanWork)
                        add("dirty_work", dirtyWork)
                    }
                }
            } finally {
                state.close()
            }
        check(state.retained == listOf(0L, 0L, 0L) && state.subscriptions == 0)
        val terminal = indexRetention(state)
        check(terminal.get("reserved_payload_bytes").asLong == 0L)
        return records.onEach { it.add("terminal_index_retention", terminal) }
    }

    private class GlyphTraceEvidence(
        val document: ByteArray,
        val trace: List<MinecraftFontGlyph>,
        val scalars: List<Int>,
        val predicates: Long,
    )

    private fun verifyTrace(
        scenario: UnihexOverrideScenario,
        state: UnihexOverrideBenchmark.OverrideSession,
    ): GlyphTraceEvidence {
        val source = UnihexOverrideAssets.source(scenario)
        val document = checkNotNull(source.read("assets/minecraft/font/default.json"))
        val ranges =
            JsonParser
                .parseString(document.toString(Charsets.UTF_8))
                .asJsonObject
                .getAsJsonArray("providers")
                .single()
                .asJsonObject
                .getAsJsonArray("size_overrides")
        val trace = state.trace()
        val scalars = UnihexOverrideAssets.scalars(scenario)
        check(trace.size == scalars.size)
        var predicates = 0L
        for ((glyph, scalar) in trace.zip(scalars)) {
            val reference = originalGlyph(ranges, scalar)
            equalGlyph(glyph, reference.first)
            predicates += reference.second
        }
        return GlyphTraceEvidence(document, trace, scalars, predicates)
    }

    private fun originalGlyph(
        ranges: JsonArray,
        scalar: Int,
    ): Pair<MinecraftFontGlyph, Long> {
        if ((scalar in 65..192).not() && (scalar in 0x1F600..0x1F63F).not()) {
            val pixels = IntArray(40) { if (it % 5 in setOf(0, 4) || it / 5 in setOf(0, 7)) -1 else 0 }
            return MinecraftFontGlyph(6f, 0f, 0f, 5f, 8f, createDrawImage(IntSize(5, 8), pixels)) to 0L
        }
        var winner: JsonObject? = null
        var predicates = 0L
        for (value in ranges) {
            predicates++
            val candidate = value.asJsonObject
            val first = candidate.get("from").asString.codePointAt(0)
            val last = candidate.get("to").asString.codePointAt(0)
            if (first <= scalar && scalar <= last) {
                winner = candidate
                break
            }
        }
        val left = winner?.get("left")?.asInt ?: 0
        val right = winner?.get("right")?.asInt ?: 7
        val width = Math.addExact(Math.subtractExact(right, left), 1)
        val pixels = IntArray(width * 16) { if (left + it % width in setOf(0, 7)) -1 else 0 }
        return MinecraftFontGlyph(width / 2f + 1f, 0f, 0f, width / 2f, 8f, createDrawImage(IntSize(width, 16), pixels), boldOffset = 0.5f, shadowOffset = 0.5f) to predicates
    }

    private fun indexRetention(state: UnihexOverrideBenchmark.OverrideSession): JsonObject {
        val engine =
            state.javaClass
                .getDeclaredField("engine")
                .apply { isAccessible = true }
                .get(state) as MinecraftFontEngine
        val field = indexField(engine)
        val indexes = (field?.apply { isAccessible = true }?.get(engine) as? Map<*, *>).orEmpty()
        var segments = 0L
        var bytes = 0L
        indexes.values.forEach { value ->
            val index = checkNotNull(value)
            segments +=
                index.javaClass
                    .getDeclaredField("segmentCount")
                    .apply { isAccessible = true }
                    .getInt(index)
            bytes +=
                (
                    index.javaClass
                        .getDeclaredField("starts")
                        .apply { isAccessible = true }
                        .get(index) as IntArray
                ).size.toLong() * 8L
        }
        check(bytes <= 512 * 1024L)
        return JsonObject().apply {
            addProperty("index_implementation_present", field != null)
            addProperty("providers", indexes.size)
            addProperty("retained_segments", segments)
            addProperty("reserved_payload_bytes", bytes)
            addProperty("retains_request_history", false)
            addProperty("binary_probe_bound_source_only", if (segments == 0L) 0 else 64 - segments.countLeadingZeroBits())
            addProperty("actual_probe_instrumentation", "pending independent untimed instrumentation before formal acceptance")
        }
    }

    private fun indexField(engine: MinecraftFontEngine): Field? =
        try {
            engine.javaClass.getDeclaredField("unihexIndexes")
        } catch (expected: NoSuchFieldException) {
            // The independent baseline intentionally has no derived interval field.
            null
        }

    private fun equalGlyph(
        actual: MinecraftFontGlyph,
        expected: MinecraftFontGlyph,
    ) {
        check(actual.copy(image = null) == expected.copy(image = null))
        check(actual.image?.size == expected.image?.size)
        check(actual.image?.copyArgb()?.contentEquals(expected.image?.copyArgb()) ?: (expected.image == null))
    }

    private fun framePixels(frame: RuntimeUiFrame): String = digest(rasterizeHeadless(frame.drawCommands, frame.size).copyArgb())

    private fun digest(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(Math.multiplyExact(pixels.size, 4))
        pixels.forEach(bytes::putInt)
        return FontRasterAssets.digest(bytes.array())
    }

    private fun glyphRecord(glyph: MinecraftFontGlyph): JsonObject =
        JsonObject().apply {
            addProperty("advance_bits", glyph.advance.toBits())
            add("metric_bits", JsonArray().apply { listOf(glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset).forEach { add(it.toBits()) } })
            addProperty("channel", glyph.channel.name)
            addProperty("orientation", glyph.orientation.name)
            addProperty("width", glyph.image?.size?.width)
            addProperty("height", glyph.image?.size?.height)
            addProperty("argb_sha256", glyph.image?.let { digest(it.copyArgb()) })
        }
}
