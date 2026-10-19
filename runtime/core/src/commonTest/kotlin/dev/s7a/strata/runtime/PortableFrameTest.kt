package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiFrameWithOwnedSemantics
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun committedSemanticsSurviveKeyedReorderRemovalDetachAndCallbackFailure() {
        val probe = TestProbe()
        val first = TestProbe.ProbeId("first")
        val second = TestProbe.ProbeId("second")
        val children = mutableStateOf(listOf(first, second))
        var disposals = 0
        val session =
            createRuntimeUiSession {
                probe.root(children.value.map { key -> probe.element(key, key = key, onDispose = { disposals += 1 }) })
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(4, 1)
            val initial = session.frame(constraints)
            val expected = initial.semantics.toList()
            val retainedFirst = probe.nodeForTag(first)
            val retainedSecond = probe.nodeForTag(second)
            assertEquals(listOf("root", "first", "second").map(UiText::Literal), initial.semantics.map { it.semantics.label })

            children.value = listOf(second, first)
            val reordered = session.frame(constraints)
            assertEquals(listOf("root", "second", "first").map(UiText::Literal), reordered.semantics.map { it.semantics.label })
            assertSame(retainedFirst, probe.nodeForTag(first))
            assertSame(retainedSecond, probe.nodeForTag(second))
            assertEquals(expected, initial.semantics)
            session.detach()
            assertEquals(expected, initial.semantics)
            session.attach()
            assertEquals(reordered.semantics, session.frame(constraints).semantics)

            children.value = listOf(second)
            val removed = session.frame(constraints)
            assertEquals(listOf("root", "second").map(UiText::Literal), removed.semantics.map { it.semantics.label })
            assertEquals(1, disposals)
            val failure = IllegalStateException("Semantics callback failed")
            retainedSecond.onSemantics = { throw failure }
            retainedSecond.invalidateForTest(DirtyMask.of(DirtyPhase.Semantics))
            assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(constraints) })
            assertEquals(expected, initial.semantics)
            assertEquals(2, disposals)
        } finally {
            session.close()
        }
        assertEquals(2, disposals)
    }
}
