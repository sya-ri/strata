@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Independent literal full-image oracle for actual fixed-first/weighted-slot geometry at three output densities. */
internal class HeadlessLinearMeasurementTest {
    @Test
    fun fullRowAndColumnPixelsPreserveFixedFirstSlotsAndOriginalCommandOrder() {
        val colors = listOf(ArgbColor(0xFFFF0000.toInt()), ArgbColor(0xFF00FF00.toInt()), ArgbColor(0xFF0000FF.toInt()))
        for (horizontal in listOf(true, false)) {
            for (arrangement in Arrangement.entries) {
                val description = evaluateComponentTree {
                    if (horizontal) Row(horizontalArrangement = arrangement) {
                        Spacer(modifier = Modifier.Empty.size(2, 2).background(colors[0]))
                        Spacer(modifier = Modifier.Empty.weight(1f).size(1, 2).background(colors[1]))
                        Spacer(modifier = Modifier.Empty.weight(3f).size(1, 2).background(colors[2]))
                    } else Column(verticalArrangement = arrangement) {
                        Spacer(modifier = Modifier.Empty.size(2, 2).background(colors[0]))
                        Spacer(modifier = Modifier.Empty.weight(1f).size(2, 1).background(colors[1]))
                        Spacer(modifier = Modifier.Empty.weight(3f).size(2, 1).background(colors[2]))
                    }
                }
                for (density in listOf(1, 2, 3)) {
                    val viewport = if (horizontal) IntSize(10, 2) else IntSize(2, 10)
                    val frame = renderHeadless(description, viewport, density)
                    for (y in 0 until frame.image.size.height) {
                        for (x in 0 until frame.image.size.width) {
                            val main = (if (horizontal) x else y) / density
                            val expected = when {
                                main < 2 -> colors[0]
                                main < 4 -> colors[1]
                                else -> colors[2]
                            }
                            assertEquals(expected.value, frame.image.argbAt(x, y), "$horizontal/$arrangement/$density at ($x,$y)")
                        }
                    }
                    assertEquals(true, frame.semantics.isEmpty())
                }
            }
        }
    }
}
