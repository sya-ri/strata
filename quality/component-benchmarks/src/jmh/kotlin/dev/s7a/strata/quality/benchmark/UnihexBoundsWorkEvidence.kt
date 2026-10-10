package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Independent scalar ARGB/metric and complete Text checks for every frozen comparison row.
 * This verifier never certifies pending external method traces, owner/failure test runs or loaded native parity.
 * Those independent controls remain separate acceptance evidence and cannot be replaced by fixture construction.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object UnihexBoundsWorkEvidence {
    /**
     * Runs deterministic fixture admission with the already packaged JMH registry; no sampling loop is owned here.
     */
    @JvmStatic
    public fun verify() {
        val fixtures = listOf(UnihexBoundsBenchmark::class.java, UnihexBoundsControlBenchmark::class.java)
        val expected = UnihexBoundsRow.read().map { it.identity() }.toSet()
        check(expected.size == 132 && table("fixtures.tsv").size == 36 && table("measurements.tsv").size == 792 && table("controls.tsv").size == 30)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")) == expected)
        for (fixture in UnihexBoundsFixture.entries) {
            val bounds = UnihexBoundsBenchmark.BoundsState().also {
                it.fixture = fixture
                it.setup()
            }
            val rows = UnihexBoundsAssets.rows(fixture)
            check(bounds.bounds() == UnihexBoundsAssets.scalarBounds(fixture.width, rows))
            for (fractional in listOf(false, true)) {
                verifyGlyph(fixture, fractional)
                verifyText(fixture, fractional)
            }
        }
        for (control in UnihexBoundsControl.entries) verifyControl(control)
    }

    /**
     * Executes exactly one frozen row for an external untimed JVM method trace.
     * Setup is outside the printed begin/end boundary; a trace counts actual bounds/raster methods inside it.
     * This entry contains no timing, profiler, controller, independent sampling loop or origin certificate.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val row = UnihexBoundsRow.read().single { it.id == args[0] }
        val fixture = row.fixture
        val fractional = row.advance == UnihexBoundsRow.Advance.Fractional
        val begin = { println("Unihex operation begin ${row.id} ${row.identity()}") }
        val end = { output: Any -> println("Unihex operation end ${row.id} ${output.javaClass.name}") }
        when (row.operation) {
            UnihexBoundsRow.Operation.NaturalBounds -> {
                val state = UnihexBoundsBenchmark.BoundsState().also {
                    it.fixture = checkNotNull(fixture)
                    it.setup()
                }
                begin()
                end(state.bounds())
            }

            UnihexBoundsRow.Operation.PublicUncachedGlyph -> {
                val state = UnihexBoundsBenchmark.GlyphState().also {
                    it.fixture = checkNotNull(fixture)
                    it.fractional = fractional
                    it.setup()
                }
                try {
                    begin()
                    end(state.glyph())
                } finally {
                    state.close()
                }
            }

            UnihexBoundsRow.Operation.CompleteDirtyTextFrame -> {
                val state = UnihexBoundsBenchmark.TextState().also {
                    it.fixture = checkNotNull(fixture)
                    it.fractional = fractional
                    it.setup()
                }
                try {
                    state.monitorWork()
                    begin()
                    end(state.update())
                    println("Unihex frame work ${row.id} ${state.work}")
                } finally {
                    state.close()
                }
            }

            UnihexBoundsRow.Operation.Control -> {
                val state = UnihexBoundsControlBenchmark.ControlState().also {
                    it.control = checkNotNull(row.control)
                    it.setup()
                }
                try {
                    begin()
                    end(state.operation())
                } finally {
                    state.close()
                }
            }
        }
    }

    private fun table(name: String): List<List<String>> =
        UnihexBoundsAssets.bytes(name).toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotEmpty() }.map { it.split('\t') }.toList()

    private fun verifyGlyph(
        fixture: UnihexBoundsFixture,
        fractional: Boolean,
    ) {
        val state = UnihexBoundsBenchmark.GlyphState().also {
            it.fixture = fixture
            it.fractional = fractional
            it.setup()
        }
        val first: MinecraftFontGlyph
        try {
            first = state.glyph()
            checkGlyph(fixture, fractional, first)
            repeat(16) {
                val next = state.glyph()
                check(first.image !== next.image)
                checkGlyph(fixture, fractional, next)
            }
        } finally {
            state.close()
        }
        checkGlyph(fixture, fractional, first)
    }

    private fun verifyText(
        fixture: UnihexBoundsFixture,
        fractional: Boolean,
    ) {
        val state = UnihexBoundsBenchmark.TextState().also {
            it.fixture = fixture
            it.fractional = fractional
            it.setup()
        }
        val old: RuntimeUiFrame
        try {
            state.monitorWork()
            old = state.idle()
            check(state.idle() === old)
            WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 2L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L, UiRenderMetric.Semantics.name to 0L)).verify(PerformanceJson.work(state.work))
            checkFrame(fixture, fractional, old, "A")
            val changed = state.update()
            check(changed !== old)
            checkFrame(fixture, fractional, changed, "B")
            WorkExpectation(minimum = mapOf(UiRenderMetric.ContentEvaluation.name to 1L, UiRenderMetric.Measure.name to 1L, UiRenderMetric.Paint.name to 1L, UiRenderMetric.Semantics.name to 1L)).verify(PerformanceJson.work(state.work))
            check(state.ownership == listOf(1, 1))
            check(state.pointer() == InputResult.Ignored)
            checkFrame(fixture, fractional, state.reattach(), "B")
            println("Unihex Text ${fixture.name} fractional=$fractional work=${state.work}")
        } finally {
            state.close()
        }
        checkFrame(fixture, fractional, old, "A")
    }

    private fun verifyControl(control: UnihexBoundsControl) {
        val state = UnihexBoundsControlBenchmark.ControlState().also {
            it.control = control
            it.setup()
        }
        try {
            val first = state.operation()
            val second = state.operation()
            when (control) {
                UnihexBoundsControl.WarmRasterHit -> {
                    check(first === second && first is MinecraftFontGlyph)
                    checkGlyph(state.fixture, state.fractional, first)
                }

                UnihexBoundsControl.Evicted8Integer, UnihexBoundsControl.Evicted32Fractional -> {
                    check(first is MinecraftFontGlyph && second is MinecraftFontGlyph && first.image !== second.image)
                    checkGlyph(state.fixture, state.fractional, first)
                    checkGlyph(state.fixture, state.fractional, second)
                    check(state.retained[0] == 1L && state.retained[1] <= 16L * 1024 * 1024 && state.retained[2] == 0L)
                }

                UnihexBoundsControl.MatchingOverride -> checkGlyph(state.fixture, state.fractional, first as MinecraftFontGlyph, -1..33)
                UnihexBoundsControl.NoMatchingOverride, UnihexBoundsControl.EngineLifecycle -> checkGlyph(state.fixture, state.fractional, first as MinecraftFontGlyph)
                UnihexBoundsControl.CleanTextFrame -> {
                    check(first === second)
                    checkFrame(state.fixture, state.fractional, first as RuntimeUiFrame, "A")
                }

                UnihexBoundsControl.AbsentSparseGlyph -> {
                    check(first is MinecraftFontGlyph && second is MinecraftFontGlyph && first == second)
                    check(first.advance.toRawBits() == 6.0f.toRawBits())
                }

                UnihexBoundsControl.Bitmap -> {
                    val image = checkNotNull((first as MinecraftFontGlyph).image)
                    check(image.size == IntSize(8, 8))
                    val expected = IntArray(64) { index -> if ((index % 8 + index / 8) % 2 == 0) -1 else 0x00ffffff }
                    check(image.copyArgb().contentEquals(expected))
                }

                UnihexBoundsControl.TrueType -> {
                    check(first is MinecraftFontGlyph && second is MinecraftFontGlyph && first.image !== second.image)
                    check(checkNotNull(first.image).copyArgb().contentEquals(checkNotNull(second.image).copyArgb()))
                    check(first.copy(image = null) == second.copy(image = null))
                }

                UnihexBoundsControl.SnapshotLoad -> check(first !== second)
                UnihexBoundsControl.SnapshotReplacement -> {
                    checkFrame(UnihexBoundsFixture.W32CenteredSparse, true, first as RuntimeUiFrame, "A")
                    checkFrame(UnihexBoundsFixture.W32DisjointExtrema, true, second as RuntimeUiFrame, "A")
                }
            }
        } finally {
            state.close()
        }
    }

    private fun checkGlyph(
        fixture: UnihexBoundsFixture,
        fractional: Boolean,
        glyph: MinecraftFontGlyph,
        bounds: IntRange = UnihexBoundsAssets.scalarBounds(fixture.width, UnihexBoundsAssets.rows(fixture)),
    ) {
        val rows = UnihexBoundsAssets.rows(fixture)
        val width = bounds.last - bounds.first + 1
        val expected = IntArray(width * 16) { index -> if (UnihexBoundsAssets.scalarInk(fixture.width, rows, bounds.first + index % width, index / width)) -1 else 0 }
        val image = checkNotNull(glyph.image)
        check(image.size == IntSize(width, 16) && image.copyArgb().contentEquals(expected))
        val advance = if (fractional) width / 2.0f + 1.0f else (width / 2 + 1).toFloat()
        check(listOf(glyph.advance, glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset).map(Float::toRawBits) == listOf(advance, 0.0f, 0.0f, width / 2.0f, 8.0f, 0.5f, 0.5f).map(Float::toRawBits))
        check(glyph.oversizedRasterSize == null)
    }

    private fun checkFrame(
        fixture: UnihexBoundsFixture,
        fractional: Boolean,
        frame: RuntimeUiFrame,
        label: String,
    ) {
        val rows = UnihexBoundsAssets.rows(fixture)
        val bounds = UnihexBoundsAssets.scalarBounds(fixture.width, rows)
        val columns = bounds.last - bounds.first + 1
        val advance = if (fractional) columns / 2.0f + 1.0f else (columns / 2 + 1).toFloat()
        val semantic = frame.semantics.single { it.semantics.role == SemanticsRole.Text }
        check(semantic.semantics.label == UiText.Literal(label))
        check(semantic.bounds == IntRect(0, 0, ceil(advance.toDouble()).toInt(), 9))
        check(frame.size == IntSize(320, 40))
        for (density in 1..3) {
            val pixels = IntArray(320 * density * 40 * density) { index ->
                val x = (index % (320 * density) + 0.5f) / density.toFloat()
                val y = (index / (320 * density) + 0.5f) / density.toFloat()
                if (x < columns / 2.0f && y < 8.0f) {
                    val u = x / (columns / 2.0f)
                    val v = y / 8.0f
                    val sourceX = floor(0.01f * (1f - u) + (columns - 0.01f) * u).toInt().coerceIn(0, columns - 1)
                    val sourceY = floor(0.01f * (1f - v) + (16 - 0.01f) * v).toInt().coerceIn(0, 15)
                    if (UnihexBoundsAssets.scalarInk(fixture.width, rows, bounds.first + sourceX, sourceY)) 0xff404040.toInt() else 0
                } else {
                    0
                }
            }
            check(rasterizeHeadless(frame.drawCommands, frame.size, density).copyArgb().contentEquals(pixels)) { "Independent full Text ARGB mismatch: $fixture fractional=$fractional density=$density" }
        }
    }
}
