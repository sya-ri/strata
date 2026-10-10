@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Independent exact portable pixels and geometry remain unchanged after complete root validation rejects a duplicate.
 */
internal class HeadlessDescriptionValidationTest {
    @Test
    fun rejectedRootPreservesExactPixelsGeometrySemanticsAndLifecycle() {
        val probe = HeadlessProbe()
        fun description(duplicate: Boolean) =
            evaluateComponentTree {
                Row {
                    element(HeadlessPrimitive(probe, color = ArgbColor(0xFFFF0000.toInt()), label = "red", key = ElementKey(0)))
                    element(HeadlessPrimitive(probe, color = ArgbColor(0xFF0000FF.toInt()), label = "blue", key = ElementKey(if (duplicate) 0 else 1)))
                }
            }
        UiTree().use { tree ->
            tree.update(description(false))
            val viewport = IntSize(4, 2)
            assertEquals(viewport, tree.measure(Constraints.fixed(viewport.width, viewport.height)))
            tree.layout()
            val commands = tree.paint()
            val semantics = tree.semantics()
            val lifecycle = probe.lifecycle.toList()
            val creations = probe.creations
            for (scale in 1..3) {
                val before = rasterizeHeadless(commands, viewport, scale)
                assertThrows<IllegalArgumentException> { tree.update(description(true)) }
                assertSame(commands, tree.paint())
                assertEquals(semantics, tree.semantics())
                val after = rasterizeHeadless(tree.paint(), viewport, scale)
                for (y in 0 until before.size.height) {
                    for (x in 0 until before.size.width) {
                        val expected = if (x < 2 * scale) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
                        assertEquals(expected, before.argbAt(x, y))
                        assertEquals(expected, after.argbAt(x, y))
                    }
                }
            }
            assertEquals(lifecycle, probe.lifecycle)
            assertEquals(creations, probe.creations)
            assertEquals(2, probe.measures)
            assertEquals(2, probe.layouts)
            tree.update(description(false))
            assertEquals(creations, probe.creations)
        }
    }
}
