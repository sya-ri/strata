package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies callback reentrancy rejection across update, pipeline, and cleanup callbacks.
 */
internal class CallbackReentrancyTest {
    @Test
    fun measureReentrancyPoisonsAndCleans() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("root"), onMeasure = { tree.update(probe.root(emptyList())) }))

        assertThrows(IllegalStateException::class.java) { tree.measure(Constraints(maxWidth = 10, maxHeight = 10)) }
        assertPoisonedAndCleaned(tree, probe, id("root"))
        tree.close()
    }

    @Test
    fun layoutReentrancyPoisonsAndCleans() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("root"), onLayout = { tree.update(probe.root(emptyList())) }))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))

        assertThrows(IllegalStateException::class.java) { tree.layout() }
        assertPoisonedAndCleaned(tree, probe, id("root"))
        tree.close()
    }

    @Test
    fun paintReentrancyPoisonsAndCleans() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("root"), onPaint = { tree.update(probe.root(emptyList())) }))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertThrows(IllegalStateException::class.java) { tree.paint() }
        assertPoisonedAndCleaned(tree, probe, id("root"))
        tree.close()
    }

    @Test
    fun inputReentrancyPoisonsAndCleans() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("root"), onInput = { tree.update(probe.root(emptyList())) }))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertThrows(IllegalStateException::class.java) {
            tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        }
        assertPoisonedAndCleaned(tree, probe, id("root"))
        tree.close()
    }

    @Test
    fun semanticsReentrancyPoisonsAndCleans() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("root"), onSemantics = { tree.update(probe.root(emptyList())) }))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertThrows(IllegalStateException::class.java) { tree.semantics() }
        assertPoisonedAndCleaned(tree, probe, id("root"))
        tree.close()
    }

    @Test
    fun detachAndDisposeReentrancyLeavesCloseTerminal() {
        val detachProbe = TestProbe()
        val detachTree = UiTree()
        detachTree.update(detachProbe.element(id("detach")))
        detachProbe.nodeForTag(id("detach")).onDetach = {
            detachTree.update(detachProbe.root(emptyList()))
        }

        assertThrows(IllegalStateException::class.java) { detachTree.close() }
        assertEquals(TreeState.Closed, detachTree.state)
        assertEquals(
            listOf(
                TestProbe.Event.Attach(id("detach")),
                TestProbe.Event.Detach(id("detach")),
                TestProbe.Event.Dispose(id("detach")),
            ),
            detachProbe.events,
        )
        detachTree.close()
        assertEquals(3, detachProbe.events.size)

        val disposeProbe = TestProbe()
        val disposeTree = UiTree()
        disposeTree.update(disposeProbe.element(id("dispose")))
        disposeProbe.nodeForTag(id("dispose")).onDispose = {
            disposeTree.update(disposeProbe.root(emptyList()))
        }

        assertThrows(IllegalStateException::class.java) { disposeTree.close() }
        assertEquals(TreeState.Closed, disposeTree.state)
        assertEquals(
            listOf(
                TestProbe.Event.Attach(id("dispose")),
                TestProbe.Event.Detach(id("dispose")),
                TestProbe.Event.Dispose(id("dispose")),
            ),
            disposeProbe.events,
        )
        disposeTree.close()
        assertEquals(3, disposeProbe.events.size)
    }

    /**
     * Asserts the common poisoned-tree cleanup contract for one callback.
     */
    private fun assertPoisonedAndCleaned(
        tree: UiTree,
        probe: TestProbe,
        tag: TestProbe.ProbeId,
    ) {
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(TestProbe.Event.Attach(tag), TestProbe.Event.Detach(tag), TestProbe.Event.Dispose(tag)),
            probe.events,
        )
    }

    private fun id(value: String): TestProbe.ProbeId = TestProbe.ProbeId(value)
}
