package dev.s7a.strata.runtime

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies defensive retained-tree traversal invariants that public pipelines cannot isolate.
 */
internal class PipelineInvariantTest {
    @Test
    fun pendingMeasureTraversesMeasuredChildrenWhenParentIsClean() {
        val probe = TestProbe()
        val childDescription = probe.element(TestProbe.ProbeId("child"))
        val rootDescription = probe.root(listOf(childDescription))
        val root = RetainedNode(rootDescription, probe.create(rootDescription), null)
        val child = RetainedNode(childDescription, probe.create(childDescription), root)
        root.children.add(child)
        root.measured = true
        root.measuredChildren = setOf(0)
        root.dirty = DirtyMask.None
        child.measured = true
        child.placed = true
        child.dirty = DirtyMask.of(DirtyPhase.Measure)

        val pipeline = Pipeline(ThreadGuard.currentThread())

        assertEquals(DirtyMask.None, root.dirty)
        assertTrue(pipeline.hasPendingMeasure(root))
    }
}
