@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Compares retained Grid commands, full pixels, semantics and input with independent integer geometry.
 */
internal class HeadlessGridTrackParityTest {
    @Test
    fun currentGeometrySurvivesAlignmentMembershipSizeConstraintAndTrackChanges() {
        val initial = Scene(List(3) { Cell(it, IntSize(it + 2, 3 - it)) }, 2)
        val state = mutableStateOf(initial)
        val hits = ArrayList<Int>()
        val session =
            createRuntimeUiSession {
                val scene = state.value
                evaluateComponentTree {
                    Grid(scene.columns, horizontalSpacing = scene.spacing, verticalSpacing = scene.spacing, contentAlignment = scene.alignment) {
                        scene.cells.forEach { cell ->
                            var modifier =
                                Modifier.Empty
                                    .size(cell.size.width, cell.size.height)
                                    .background(cell.color)
                                    .semantics(cell.semantics)
                                    .onPress { hits += cell.id }
                            cell.alignment?.let { modifier = modifier.align(it) }
                            Spacer(key = ElementKey(cell.id), modifier = modifier)
                        }
                    }
                }
            }
        session.attach()
        val partial = initial.copy(cells = List(128) { Cell(it, IntSize(it % 7 + 1, it % 5 + 1)) }, columns = 7)
        val large = partial.copy(cells = List(4_096) { Cell(it, IntSize(it % 7 + 1, it % 5 + 1)) }, columns = 64)
        val models =
            listOf(
                initial,
                initial.copy(alignment = Alignment.BottomEnd),
                initial.copy(cells = initial.cells.map { it.copy(alignment = Alignment.Center) }),
                initial.copy(cells = initial.cells.reversed()),
                initial.copy(cells = initial.cells.take(2)),
                initial.copy(cells = listOf(initial.cells[0], Cell(9, IntSize(5, 4)))),
                initial.copy(cells = listOf(initial.cells[0].copy(size = initial.cells[1].size), initial.cells[1].copy(size = initial.cells[0].size))),
                initial.copy(columns = 1, spacing = 3),
                partial,
                large,
                initial.copy(cells = initial.cells.take(1)),
                initial.copy(cells = emptyList()),
            )
        try {
            for (scene in models) {
                state.value = scene
                for (viewport in listOf(IntSize(128, 128), IntSize(5, 4))) {
                    verifyGeometry(session, scene, viewport, hits)
                }
            }
            val oldFrame = session.frame(Constraints.fixed(5, 4))
            val oldImage = rasterizeHeadless(oldFrame.drawCommands, IntSize(5, 4))
            val oldPixels = oldImage.copyArgb()
            state.value = initial
            session.frame(Constraints.fixed(5, 4))
            session.close()
            assertArrayEquals(oldPixels, oldImage.copyArgb())
            assertEquals(emptyList<DrawCommand>(), oldFrame.drawCommands)
        } finally {
            session.close()
        }
    }

    private fun verifyGeometry(
        session: RuntimeUiSession,
        scene: Scene,
        viewport: IntSize,
        hits: MutableList<Int>,
    ) {
        val frame = session.frame(Constraints.fixed(viewport.width, viewport.height))
        val bounds = referenceBounds(scene, viewport)
        val commands = bounds.mapIndexed { index, rect -> DrawCommand.FillRectangle(rect, scene.cells[index].color) }
        assertEquals(commands, frame.drawCommands)
        assertEquals(bounds.mapIndexed { index, rect -> SemanticsEntry(rect, scene.cells[index].semantics) }, frame.semantics)
        assertSame(frame, session.frame(Constraints.fixed(viewport.width, viewport.height)))
        for (density in 1..4) {
            val image = rasterizeHeadless(frame.drawCommands, viewport, density)
            assertArrayEquals(referencePixels(scene, bounds, viewport, density), image.copyArgb())
        }
        for (index in bounds.indices) {
            val rect = bounds[index]
            if (rect.width == 0 || rect.height == 0) continue
            hits.clear()
            assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Press(IntOffset(rect.left, rect.top), PointerButton.Primary)))
            assertEquals(listOf(scene.cells[index].id), hits)
        }
    }

    // Independent track-by-track scans use constrained leaf extents and live cell/default alignment.
    private fun referenceBounds(
        scene: Scene,
        viewport: IntSize,
    ): List<IntRect> {
        val sizes = scene.cells.map { IntSize(minOf(it.size.width, viewport.width), minOf(it.size.height, viewport.height)) }
        val widths =
            List(minOf(scene.columns, sizes.size)) { column ->
                sizes.filterIndexed { index, _ -> index % scene.columns == column }.maxOf { it.width }
            }
        val heights = sizes.chunked(scene.columns).map { row -> row.maxOf { it.height } }
        val columnStarts = widths.runningFold(0) { offset, width -> offset + width + scene.spacing }
        val rowStarts = heights.runningFold(0) { offset, height -> offset + height + scene.spacing }
        return sizes.mapIndexed { index, size ->
            val column = index % scene.columns
            val row = index / scene.columns
            val alignment = scene.cells[index].alignment ?: scene.alignment
            val slackX = widths[column] - size.width
            val slackY = heights[row] - size.height
            val x =
                columnStarts[column] +
                    when (alignment.horizontalAlignment) {
                        HorizontalAlignment.Start -> 0
                        HorizontalAlignment.Center -> slackX / 2
                        HorizontalAlignment.End -> slackX
                    }
            val y =
                rowStarts[row] +
                    when (alignment.verticalAlignment) {
                        VerticalAlignment.Top -> 0
                        VerticalAlignment.Center -> slackY / 2
                        VerticalAlignment.Bottom -> slackY
                    }
            IntRect(x, y, x + size.width, y + size.height)
        }
    }

    // Opaque integer cells can be independently filled in declared order without calling a raster helper.
    private fun referencePixels(
        scene: Scene,
        bounds: List<IntRect>,
        viewport: IntSize,
        density: Int,
    ): IntArray {
        val width = viewport.width * density
        val result = IntArray(width * viewport.height * density)
        bounds.forEachIndexed { index, rect ->
            for (y in maxOf(0, rect.top) * density until minOf(viewport.height, rect.bottom) * density) {
                for (x in maxOf(0, rect.left) * density until minOf(viewport.width, rect.right) * density) {
                    result[y * width + x] = scene.cells[index].color.value
                }
            }
        }
        return result
    }

    /**
     * Complete current declaration input; tests retain no historical runtime geometry.
     */
    private data class Scene(
        val cells: List<Cell>,
        val columns: Int,
        val spacing: Int = 1,
        val alignment: Alignment = Alignment.TopStart,
    )

    /**
     * Keyed opaque cell with independently derived paint, semantics and optional parent alignment.
     */
    private data class Cell(
        val id: Int,
        val size: IntSize,
        val alignment: Alignment? = null,
    ) {
        val color = ArgbColor(0xFF000000.toInt() or (id * 7_919 and 0xFFFFFF))
        val semantics = Semantics(label = UiText.Literal(id.toString()))
    }
}
