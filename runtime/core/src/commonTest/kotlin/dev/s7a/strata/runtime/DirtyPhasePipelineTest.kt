package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Independently verifies every public phase subset through propagation and complete retained frames on each shared target.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class DirtyPhasePipelineTest {
    @Test
    fun everyMaskPreservesExactExpansionAncestorFlagsAndRevisions() {
        for (bits in 0 until 16) {
            val phases = DirtyPhase.entries.filter { bits and (1 shl it.ordinal) != 0 }
            val mask = DirtyMask.of(*phases.reversed().toTypedArray(), *phases.toTypedArray())
            val probe = TestProbe()
            val rootDescription = probe.root(emptyList())
            val leafDescription = probe.element(TestProbe.ProbeId("leaf"))
            val root = RetainedNode(rootDescription, probe.create(rootDescription), null)
            val leaf = RetainedNode(leafDescription, probe.create(leafDescription), root)
            leaf.parent = root
            root.dirty = DirtyMask.None
            leaf.dirty = DirtyMask.None
            root.paintSubtreeDirty = false
            leaf.paintSubtreeDirty = false
            val tracker = DirtyTracker()
            repeat(2) { tracker.record(leaf, mask) }

            val measure = DirtyPhase.Measure in phases
            val layout = measure || DirtyPhase.Layout in phases
            val paint = layout || DirtyPhase.Paint in phases
            val semantics = layout || DirtyPhase.Semantics in phases
            assertEquals(measure, DirtyPhase.Measure in leaf.dirty)
            assertEquals(layout, DirtyPhase.Layout in leaf.dirty)
            assertEquals(paint, DirtyPhase.Paint in leaf.dirty)
            assertEquals(semantics, DirtyPhase.Semantics in leaf.dirty)
            assertEquals(if (measure) DirtyMask.All else DirtyMask.None, root.dirty)
            assertEquals(paint, leaf.paintSubtreeDirty)
            assertEquals(paint, root.paintSubtreeDirty)
            assertEquals(if (phases.isEmpty()) 0L else 2L, tracker.revision)
            assertEquals(0L, tracker.structureRevision)
        }
    }

    @Test
    fun allSubsetsPreserveCallbacksOrderedFramesCleanReuseAndLifecycleWithBothDiagnosticsModes() {
        for (monitoring in listOf(false, true)) {
            for (bits in 0 until 16) {
                val phases = DirtyPhase.entries.filter { bits and (1 shl it.ordinal) != 0 }
                val mask = DirtyMask.of(*phases.reversed().toTypedArray(), *phases.toTypedArray())
                val probe = TestProbe()
                val leafId = TestProbe.ProbeId("leaf")
                val siblingId = TestProbe.ProbeId("sibling")
                val branchId = TestProbe.ProbeId("branch")
                val description = probe.root(listOf(probe.element(branchId, children = listOf(probe.element(leafId))), probe.element(siblingId)))
                val session = createRuntimeUiSession { description }
                try {
                    session.attach()
                    val constraints = Constraints.fixed(8, 8)
                    val original = session.frame(constraints)
                    val commands = original.drawCommands.toList()
                    val semantics = original.semantics.toList()
                    val monitor = if (monitoring) session.startRenderMonitoring() else null
                    try {
                        val before = listOf(probe.measureCalls, probe.layoutCalls, probe.paintCalls, probe.semanticsCalls)
                        val leaf = probe.nodeForTag(leafId)
                        repeat(2) { leaf.invalidateForTest(mask) }
                        val frame = session.frame(constraints)
                        val measure = DirtyPhase.Measure in phases
                        val layout = measure || DirtyPhase.Layout in phases
                        val paint = layout || DirtyPhase.Paint in phases
                        val semantic = layout || DirtyPhase.Semantics in phases
                        val expected =
                            listOf(
                                if (measure) 3 else 0,
                                when {
                                    measure -> 3
                                    layout -> 1
                                    else -> 0
                                },
                                when {
                                    measure -> 3
                                    paint -> 1
                                    else -> 0
                                },
                                when {
                                    measure -> 3
                                    semantic -> 1
                                    else -> 0
                                },
                            )
                        val after = listOf(probe.measureCalls, probe.layoutCalls, probe.paintCalls, probe.semanticsCalls)
                        assertEquals(expected, after.zip(before) { end, start -> end - start }, "Callbacks for mask $bits, monitoring=$monitoring")
                        monitor?.snapshot()?.let { snapshot ->
                            listOf(UiRenderMetric.Measure, UiRenderMetric.Layout, UiRenderMetric.Paint, UiRenderMetric.Semantics).forEachIndexed { index, metric ->
                                assertEquals(expected[index].toLong(), snapshot.counts[metric], "$metric for mask $bits")
                            }
                            assertEquals(if (measure) 4L else 0L, snapshot.counts[UiRenderMetric.AncestorInvalidation])
                            assertEquals(if (phases.isEmpty()) 0L else 2L, snapshot.counts[UiRenderMetric.LocalInvalidation])
                        }
                        assertEquals(commands, frame.drawCommands)
                        assertEquals(semantics, frame.semantics)
                        assertEquals(commands, original.drawCommands)
                        assertEquals(semantics, original.semantics)
                        if (phases.isEmpty()) assertSame(original, frame)
                        assertSame(frame, session.frame(constraints))
                        assertEquals(after, listOf(probe.measureCalls, probe.layoutCalls, probe.paintCalls, probe.semanticsCalls))
                    } finally {
                        monitor?.close()
                    }
                    session.detach()
                    assertEquals(0, probe.events.count { it is TestProbe.Event.Detach })
                    assertEquals(semantics, original.semantics)
                    session.attach()
                    assertEquals(4, probe.events.count { it is TestProbe.Event.Attach })
                    assertEquals(commands, session.frame(constraints).drawCommands)
                } finally {
                    session.close()
                }
                assertEquals(4, probe.events.count { it is TestProbe.Event.Dispose })
                assertEquals(4, probe.events.count { it is TestProbe.Event.Detach })
                assertFailsWith<IllegalStateException> { probe.nodeForTag(leafId).invalidateForTest(mask) }
            }
        }
    }

    @Test
    fun everyCallbackFailurePreservesOlderFramesAndReleasesTheOwner() {
        for (phase in DirtyPhase.entries) {
            val probe = TestProbe()
            val leafId = TestProbe.ProbeId("leaf")
            val session = createRuntimeUiSession { probe.root(listOf(probe.element(leafId))) }
            try {
                session.attach()
                val constraints = Constraints.fixed(8, 8)
                val original = session.frame(constraints)
                val commands = original.drawCommands.toList()
                val semantics = original.semantics.toList()
                val leaf = probe.nodeForTag(leafId)
                val failure = IllegalStateException("Injected phase failure")
                val fail: () -> Unit = { throw failure }
                when (phase) {
                    DirtyPhase.Measure -> leaf.onMeasure = fail
                    DirtyPhase.Layout -> leaf.onLayout = fail
                    DirtyPhase.Paint -> leaf.onPaint = fail
                    DirtyPhase.Semantics -> leaf.onSemantics = fail
                }
                leaf.invalidateForTest(DirtyMask.All)
                assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(constraints) })
                assertEquals(2, probe.events.count { it is TestProbe.Event.Dispose })
                assertEquals(2, probe.events.count { it is TestProbe.Event.Detach })
                assertEquals(commands, original.drawCommands)
                assertEquals(semantics, original.semantics)
                assertFailsWith<IllegalStateException> { leaf.invalidateForTest(DirtyMask.None) }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun emptyTreeAndNoneInvalidationKeepPhaseWorkAndRevisionEmpty() {
        UiTree().use { tree ->
            assertEquals(IntSize.Zero, tree.measure(Constraints()))
            tree.layout()
            assertEquals(emptyList(), tree.paint())
            assertEquals(emptyList(), tree.semantics())
            assertEquals(0L, tree.currentRevision())
        }
    }
}
