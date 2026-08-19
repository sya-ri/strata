package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies poisoning and ownership cleanup for every retained pipeline callback stage.
 */
internal class PipelineFailureTest {
    @Test
    fun pipelineFailurePreservesPrimaryAndClosesWithoutRepeatingCleanup() {
        PipelineStage.entries.forEach { stage ->
            val failure = IllegalStateException("pipeline failure")
            val probe = probeFor(stage, failure)
            val tree = UiTree()
            tree.update(probe.root(emptyList()))
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    runStage(tree, stage)
                }
            assertSame(failure, thrown)
            assertEquals(TreeState.Poisoned, tree.state)
            assertEquals(
                listOf(
                    TestProbe.Event.Attach(TestProbe.ProbeId("root")),
                    TestProbe.Event.Detach(TestProbe.ProbeId("root")),
                    TestProbe.Event.Dispose(TestProbe.ProbeId("root")),
                ),
                probe.events,
            )
            assertThrows(IllegalStateException::class.java) {
                tree.measure(Constraints.fixed(10, 10))
            }
            val eventsBeforeClose = probe.events.toList()
            tree.close()
            assertEquals(TreeState.Closed, tree.state)
            assertEquals(eventsBeforeClose, probe.events)
            tree.close()
        }
    }

    private fun probeFor(
        stage: PipelineStage,
        failure: Throwable,
    ): TestProbe {
        val tag = TestProbe.ProbeId("root")
        return when (stage) {
            PipelineStage.Measure -> TestProbe(failingMeasureTag = tag, measureFailure = failure)
            PipelineStage.Layout -> TestProbe(failingLayoutTag = tag, layoutFailure = failure)
            PipelineStage.Paint -> TestProbe(failingPaintTag = tag, paintFailure = failure)
            PipelineStage.Input -> TestProbe(failingInputTag = tag, inputFailure = failure)
            PipelineStage.Semantics -> TestProbe(failingSemanticsTag = tag, semanticsFailure = failure)
        }
    }

    private fun runStage(
        tree: UiTree,
        stage: PipelineStage,
    ) {
        when (stage) {
            PipelineStage.Measure -> {
                tree.measure(Constraints.fixed(10, 10))
            }

            PipelineStage.Layout -> {
                tree.measure(Constraints.fixed(10, 10))
                tree.layout()
            }

            PipelineStage.Paint -> {
                tree.measure(Constraints.fixed(10, 10))
                tree.layout()
                tree.paint()
            }

            PipelineStage.Input -> {
                tree.measure(Constraints.fixed(10, 10))
                tree.layout()
                tree.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))
            }

            PipelineStage.Semantics -> {
                tree.measure(Constraints.fixed(10, 10))
                tree.layout()
                tree.semantics()
            }
        }
    }

    /**
     * Retained pipeline stages used by the failure matrix.
     */
    private enum class PipelineStage {
        /**
         * Measurement callback stage.
         */
        Measure,

        /**
         * Layout callback stage.
         */
        Layout,

        /**
         * Paint callback stage.
         */
        Paint,

        /**
         * Pointer input callback stage.
         */
        Input,

        /**
         * Semantics callback stage.
         */
        Semantics,
    }
}
