package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies modifier virtual ancestry, pipeline capabilities, and failure cutoff behavior.
 */
internal class ModifierRuntimePipelineTest {
    @Test
    fun passThroughModifierGetsOneVirtualChildAndComponentKeepsLogicalChildren() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val child = componentProbe.element(TestProbe.ProbeId("child"))
        val modifier = modifierFixture.modifier(modifierProbe, 1, ModifierTestFixture.Kind.First)

        tree.update(componentProbe.root(listOf(child), modifier = Modifier.Empty.then(modifier)))
        assertEquals(IntSize(2, 2), tree.measure(Constraints(maxWidth = 10, maxHeight = 10)))
        tree.layout()
        assertEquals(2, tree.paint().size)
        assertEquals(2, tree.semantics().size)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(listOf(ModifierTestFixture.Event.Attach(1)), modifierProbe.events)
        assertEquals(
            listOf(
                TestProbe.Event.Attach(TestProbe.ProbeId("root")),
                TestProbe.Event.Attach(TestProbe.ProbeId("child")),
            ),
            componentProbe.events,
        )
        tree.close()
    }

    @Test
    fun excludedRootModifierKeepsEffectivePipelineOperationsAvailable() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val modifier =
            modifierFixture.modifier(
                modifierProbe,
                2,
                ModifierTestFixture.Kind.First,
                ModifierTestFixture.Behavior.ExcludeChild,
            )

        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(modifier)))
        assertEquals(IntSize(3, 3), tree.measure(Constraints(maxWidth = 10, maxHeight = 10)))
        tree.layout()
        assertEquals(
            listOf(DrawCommand.FillRectangle(IntRect(0, 0, 3, 3), ArgbColor(0xFFAA00AA.toInt()))),
            tree.paint(),
        )
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(1, tree.semantics().size)
        tree.close()
    }

    @Test
    fun childFailureDoesNotObserveNewModifierAttachment() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val failure = IllegalStateException("child update")
        val childTag = TestProbe.ProbeId("child")
        val initialChild = componentProbe.element(childTag)
        tree.update(componentProbe.root(listOf(initialChild)))
        modifierProbe.events.clear()

        val failingChild = componentProbe.element(childTag, onUpdate = { throw failure })
        val modifier = modifierFixture.modifier(modifierProbe, 4, ModifierTestFixture.Kind.First)
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(listOf(failingChild), modifier = Modifier.Empty.then(modifier)))
            }

        assertSame(failure, thrown)
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(4)), modifierProbe.events)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun modifierUpdateFailurePoisonsTreeAndCleansRetainedNodeExactlyOnce() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val initial = modifierFixture.modifier(modifierProbe, 74, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(initial)))
        modifierProbe.events.clear()
        val failure = IllegalStateException("modifier update")
        val updated =
            modifierFixture.modifier(
                modifierProbe,
                74,
                ModifierTestFixture.Kind.First,
                updateFailure = failure,
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(updated)))
            }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(74),
                ModifierTestFixture.Event.Dispose(74),
            ),
            modifierProbe.events,
        )
        tree.close()
        assertThrows(IllegalStateException::class.java) {
            modifierProbe.nodes.getValue(74).invalidate(DirtyPhase.Paint)
        }
        assertEquals(1, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Detach })
        assertEquals(1, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Dispose })
    }

    @Test
    fun nodeLocalInvalidationRerunsOnlyAffectedModifierPipeline() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val modifier = modifierFixture.modifier(modifierProbe, 75, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(modifier)))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.paint()
        val node = modifierProbe.nodes.getValue(75)
        val initialMeasures = modifierProbe.measureCalls.getValue(75)
        val initialPaints = modifierProbe.paintCalls.getValue(75)
        node.invalidate(DirtyPhase.Paint)
        tree.paint()
        assertEquals(initialMeasures, modifierProbe.measureCalls.getValue(75))
        assertEquals(initialPaints + 1, modifierProbe.paintCalls.getValue(75))
        tree.close()
    }

    @Test
    fun modifierWithoutLifecycleCapabilityPassesThroughAndClosesNormally() {
        val componentProbe = TestProbe()
        val tree = UiTree()
        tree.update(
            componentProbe.root(
                emptyList(),
                modifier = Modifier.Empty.then(PassiveModifierElement()),
            ),
        )

        assertEquals(IntSize(2, 1), tree.measure(Constraints.fixed(2, 1)))
        tree.layout()
        assertEquals(1, tree.paint().size)
        tree.close()
        assertEquals(TreeState.Closed, tree.state)
    }

    private class PassiveModifierElement : ModifierElement {
        override val type: ModifierNodeType<PassiveModifierElement, PassiveModifierNode>
            get() = TYPE

        companion object {
            val TYPE: ModifierNodeType<PassiveModifierElement, PassiveModifierNode> =
                ModifierNodeType(
                    elementClass = PassiveModifierElement::class,
                    nodeClass = PassiveModifierNode::class,
                    validateLocal = { },
                    createNode = { PassiveModifierNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class PassiveModifierNode : ModifierNode()
}
