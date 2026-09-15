package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiFrame
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies frame ownership and value semantics on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableFrameTest {
    @Test
    fun frameSnapshotsCopyCollectionsAndPreserveValueEquality() {
        val bounds = IntRect(0, 0, 1, 1)
        val command = DrawCommand.FillRectangle(bounds, ArgbColor(0))
        val entry = SemanticsEntry(bounds, Semantics())
        val sourceCommands = arrayListOf<DrawCommand>(command)
        val sourceSemantics = arrayListOf(entry)
        val frame = createRuntimeUiFrame(IntSize.Zero, sourceCommands, sourceSemantics)

        sourceCommands.clear()
        sourceSemantics.clear()

        assertEquals(listOf(command), frame.drawCommands)
        assertEquals(listOf(entry), frame.semantics)

        val equalFrame = createRuntimeUiFrame(IntSize.Zero, listOf(command), listOf(entry))
        val differentFrame = createRuntimeUiFrame(IntSize(1, 0), listOf(command), listOf(entry))
        assertEquals(frame, equalFrame)
        assertEquals(frame.hashCode(), equalFrame.hashCode())
        assertNotEquals(frame, differentFrame)
    }
}
