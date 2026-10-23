package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Independent untimed output oracles for navigation, editing, Canvas replacement and discrete animation time.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object StressOutputAssertions {
    /**
     * Derives expected output from the frozen workload dimensions and operation index, never from prepared producer storage.
     */
    fun verify(
        workload: StressWorkload,
        revision: Int,
        frame: RuntimeUiFrame,
        viewport: IntSize,
    ) {
        when (workload) {
            StressWorkload.VirtualList100, StressWorkload.VirtualList1000000 -> verifyRows(workload, revision, frame)
            StressWorkload.TextField32, StressWorkload.TextField16384 -> verifyEditor(workload, revision, frame)
            StressWorkload.Canvas256, StressWorkload.Canvas1024 -> verifyCanvas(workload, revision, frame, viewport)
            StressWorkload.Animation -> verifyAnimation(revision, frame, viewport)
            else -> Unit
        }
    }

    private fun verifyRows(
        workload: StressWorkload,
        revision: Int,
        frame: RuntimeUiFrame,
    ) {
        val count = if (workload == StressWorkload.VirtualList1000000) 1_000_000 else 100
        val start = if (revision % 2 == 0) 0 else count - 10
        val rows =
            frame.semantics.filter { entry ->
                val label = entry.semantics.label as? UiText.Literal
                label?.value?.startsWith("Row ") == true && entry.bounds.top in 0 until 120
            }
        check(rows.map { it.semantics.label } == (start until start + 10).map { UiText.Literal("Row $it") }) { "Unexpected visible rows for $workload at $revision: ${rows.map { it.semantics.label to it.bounds }}; all=${frame.semantics}" }
        check(rows.map { it.bounds.top } == (0 until 10).map { it * 12 }) { "Unexpected row bounds: ${rows.map { it.bounds }}" }
    }

    private fun verifyEditor(
        workload: StressWorkload,
        revision: Int,
        frame: RuntimeUiFrame,
    ) {
        val length = if (workload == StressWorkload.TextField16384) 16_384 else 32
        val body = buildString { repeat(length) { append("abc日本語"[it % 6]) } }.take(length - 1)
        val expected = UiText.Literal((if (revision % 2 == 0) "a" else "b") + body)
        check(
            frame.semantics
                .single { it.semantics.role == SemanticsRole.TextField }
                .semantics.label == expected,
        )
    }

    private fun verifyCanvas(
        workload: StressWorkload,
        revision: Int,
        frame: RuntimeUiFrame,
        viewport: IntSize,
    ) {
        val extent = if (workload == StressWorkload.Canvas1024) 1024 else 256
        val phase = revision % 8
        val pixels =
            IntArray(extent * extent) { index ->
                if ((index + phase) % 2 == 0) 0x80426789.toInt() + phase else 0xFF987654.toInt() - phase
            }
        val image = createDrawImage(IntSize(extent, extent), pixels)
        val expected = listOf(DrawCommand.BlitImagePixels(image, IntRect(0, 0, extent, extent), IntRect(0, 0, 319, 239)))
        verifyPixels(frame, expected, viewport)
    }

    private fun verifyAnimation(
        revision: Int,
        frame: RuntimeUiFrame,
        viewport: IntSize,
    ) {
        val expected = listOf(DrawCommand.FillRectangle(IntRect(0, 0, 10, 4), ArgbColor(0xFF426789.toInt() + revision % 3)))
        verifyPixels(frame, expected, viewport)
    }

    private fun verifyPixels(
        frame: RuntimeUiFrame,
        expected: List<DrawCommand>,
        viewport: IntSize,
    ) {
        val actual = rasterizeHeadless(frame.drawCommands, viewport).copyArgb()
        val reference = rasterizeHeadless(expected, viewport).copyArgb()
        check(actual.contentEquals(reference)) { "Stress rendering differs from its independent expected scene" }
    }
}
