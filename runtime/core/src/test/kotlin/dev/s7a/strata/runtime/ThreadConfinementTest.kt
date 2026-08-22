package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies every tree operation rejects calls from a non-owner thread.
 */
internal class ThreadConfinementTest {
    @Test
    fun wrongThreadOperationsDoNotMutateOwnerState() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(emptyList()))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()

        val failures =
            listOf(
                onOtherThread { tree.update(probe.root(emptyList())) },
                onOtherThread { tree.measure(Constraints(maxWidth = 20, maxHeight = 20)) },
                onOtherThread { tree.layout() },
                onOtherThread { tree.paint() },
                onOtherThread { tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) },
                onOtherThread { tree.semantics() },
                onOtherThread { tree.state },
                onOtherThread { tree.currentRevision() },
                onOtherThread { tree.close() },
            )

        assertEquals(9, failures.size)
        failures.forEach { failure -> assertTrue(failure is IllegalStateException) }
        assertEquals(TreeState.Active, tree.state)
        tree.close()
    }

    private fun onOtherThread(block: () -> Unit): Throwable? {
        var failure: Throwable? = null
        val thread =
            Thread {
                failure = runCatching(block).exceptionOrNull()
            }
        thread.start()
        thread.join()
        return failure
    }
}
