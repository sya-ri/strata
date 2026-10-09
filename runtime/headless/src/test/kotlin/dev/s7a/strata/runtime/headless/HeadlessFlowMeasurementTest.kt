@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Literal complete pixel oracle for two real rows, all placement policies, parent overrides and densities 1/2/3. */
internal class HeadlessFlowMeasurementTest {
    @Test
    fun fullPaintedPixelsKeepOriginalGreedyRowsAndRowLocalAlignment() {
        val colors = listOf(ArgbColor(0xFFFF0000.toInt()), ArgbColor(0xFF00FF00.toInt()), ArgbColor(0xFF0000FF.toInt()))
        for (arrangement in Arrangement.entries) {
            for (alignment in VerticalAlignment.entries) {
                for (overridden in listOf(false, true)) {
                    val description = evaluateComponentTree {
                        FlowRow(horizontalSpacing = 1, verticalSpacing = 1, horizontalArrangement = arrangement, verticalAlignment = alignment) {
                            val modifier = if (overridden) Modifier.Empty.align(VerticalAlignment.Bottom) else Modifier.Empty
                            Spacer(modifier = modifier.size(2, 1).background(colors[0]))
                            Spacer(modifier = Modifier.Empty.size(2, 2).background(colors[1]))
                            Spacer(modifier = Modifier.Empty.size(2, 1).background(colors[2]))
                        }
                    }
                    val redTop = if (overridden || alignment == VerticalAlignment.Bottom) 1 else 0
                    // The first row fills all five pixels; the last row has one two-pixel child and slack three.
                    val blueLeft = when (arrangement) {
                        Arrangement.Start, Arrangement.SpaceBetween -> 0
                        Arrangement.Center, Arrangement.SpaceAround, Arrangement.SpaceEvenly -> 1
                        Arrangement.End -> 3
                    }
                    for (density in listOf(1, 2, 3)) {
                        val frame = renderHeadless(description, IntSize(5, 5), density)
                        for (y in 0 until frame.image.size.height) {
                            for (x in 0 until frame.image.size.width) {
                                val logicalX = x / density
                                val logicalY = y / density
                                val expected = when {
                                    logicalX < 2 && logicalY == redTop -> colors[0].value
                                    3 <= logicalX && logicalY < 2 -> colors[1].value
                                    blueLeft <= logicalX && logicalX < blueLeft + 2 && logicalY == 3 -> colors[2].value
                                    else -> 0
                                }
                                assertEquals(expected, frame.image.argbAt(x, y), "$arrangement/$alignment/$overridden/$density at ($x,$y)")
                            }
                        }
                        assertEquals(true, frame.semantics.isEmpty())
                    }
                }
            }
        }
    }
}
