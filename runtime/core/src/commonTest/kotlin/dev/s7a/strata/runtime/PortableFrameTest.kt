package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiFrameWithOwnedSemantics
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

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

    @Test
    fun ownedSemanticsKeepDetachedValuesAndCommandsStillCopyCallerMembership() {
        val bounds = IntRect(0, 0, 1, 1)
        val command = DrawCommand.FillRectangle(bounds, ArgbColor(0))
        val entry = SemanticsEntry(bounds, Semantics())
        val sourceCommands = arrayListOf<DrawCommand>(command)
        val ownedSemantics = buildList { add(entry) }
        val frame = createRuntimeUiFrameWithOwnedSemantics(IntSize.Zero, sourceCommands, ownedSemantics)
        sourceCommands.clear()

        assertSame(ownedSemantics, frame.semantics)
        assertEquals(listOf(command), frame.drawCommands)
        val copiedFrame = createRuntimeUiFrame(IntSize.Zero, listOf(command), listOf(entry))
        assertEquals(copiedFrame, frame)
        assertEquals(copiedFrame.hashCode(), frame.hashCode())
    }

    @Test
    fun laterOwnedFramesDoNotChangeEarlierSemanticsOrderOrBounds() {
        val first = SemanticsEntry(IntRect(0, 0, 1, 1), Semantics(disabled = true))
        val second = SemanticsEntry(IntRect(2, 0, 3, 1), Semantics(selected = true))
        val originalValues =
            buildList {
                add(first)
                add(second)
            }
        val original = createRuntimeUiFrameWithOwnedSemantics(IntSize.Zero, emptyList(), originalValues)
        val replaced = createRuntimeUiFrameWithOwnedSemantics(IntSize.Zero, emptyList(), buildList { add(second) })
        val empty = createRuntimeUiFrameWithOwnedSemantics(IntSize.Zero, emptyList(), emptyList())

        assertEquals(listOf(first, second), original.semantics)
        assertEquals(listOf(second), replaced.semantics)
        assertEquals(emptyList(), empty.semantics)
        assertNotEquals(original, replaced)
    }
}
