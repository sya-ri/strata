package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies exact-chain admission, retained ancestry and failure/lifecycle parity on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class IdenticalModifierChainTest {
    @Test
    fun freshComponentsKeepAllValidationAndUpdatesWithoutChangingCleanOutput() {
        for (count in listOf(0, 1, 8, 32)) {
            val modifiers = ModifierProbe()
            val chain = chain(List(count) { CountingModifier(modifiers) })
            val components = TestProbe()
            val tree = UiTree()
            try {
                tree.update(components.root(emptyList(), chain))
                frame(tree)
                val revision = tree.currentRevision()
                val paint = tree.paint()
                val semantics = tree.semantics()
                val nodes = modifiers.nodes.toList()
                tree.startRenderMonitoring().use { monitor ->
                    repeat(20) {
                        tree.update(components.root(emptyList(), chain))
                        assertEquals(revision, tree.currentRevision())
                        assertEquals(paint, tree.paint())
                        assertEquals(semantics, tree.semantics())
                        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
                    }
                    assertEquals(20L, monitor.snapshot().counts[UiRenderMetric.NodeUpdate])
                    assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.StructureInvalidation])
                    assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.LocalInvalidation])
                }
                assertEquals(20, components.updateCalls)
                assertEquals(21 * count, modifiers.validations)
                assertEquals(0, modifiers.updates)
                assertEquals(count, modifiers.attaches)
                nodes.forEachIndexed { index, node -> assertSame(node, modifiers.nodes[index]) }
            } finally {
                tree.close()
            }
            assertEquals(count, modifiers.detaches)
            assertEquals(count, modifiers.disposes)
            assertEquals(1, components.measureCalls)
        }
    }

    @Test
    fun distinctEquivalentChainsStillUseDescriptionIdentityForModifierUpdates() {
        val probe = ModifierProbe()
        val description = CountingModifier(probe)
        val components = TestProbe()
        val tree = UiTree()
        try {
            tree.update(components.root(emptyList(), chain(listOf(description))))
            val node = probe.nodes.single()
            tree.update(components.root(emptyList(), chain(listOf(description))))
            assertEquals(0, probe.updates)
            assertSame(node, probe.nodes.single())
            tree.update(components.root(emptyList(), chain(listOf(CountingModifier(probe)))))
            assertEquals(1, probe.updates)
            assertSame(node, probe.nodes.single())
            assertEquals(3, probe.validations)
        } finally {
            tree.close()
        }
    }

    @Test
    fun keyedReorderKeepsModifierNodesAndRefreshesEffectiveAncestry() {
        val probe = ModifierProbe()
        val modifiers = chain(List(8) { CountingModifier(probe) })
        val components = TestProbe()
        val dirty = DirtyTracker()
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), OwnerGuard(), dirty) { }
        val reconciler = Reconciler(lifecycle, dirty)
        val validator = DescriptionValidator()
        val first = TestProbe.ProbeId("first")
        val second = TestProbe.ProbeId("second")
        fun description(reverse: Boolean, parentModifiers: Modifier) =
            components.root(
                (if (reverse) listOf(second, first) else listOf(first, second)).map { id ->
                    components.element(id, key = id, modifier = modifiers)
                },
                parentModifiers,
            )
        var root = reconciler.reconcileRoot(null, description(false, Modifier.Empty))
        reconciler.markInstalled(root)
        lifecycle.attachPending(root)
        try {
            val firstChild = root.children[0]
            val secondChild = root.children[1]
            val oldModifiers = firstChild.modifiers.toList()
            val revision = dirty.structureRevision
            val unchanged = description(false, Modifier.Empty)
            validator.validate(unchanged)
            root = reconciler.reconcileRoot(root, unchanged)
            assertEquals(revision, dirty.structureRevision)
            val changed = description(true, modifiers)
            validator.validate(changed)
            root = reconciler.reconcileRoot(root, changed)
            lifecycle.attachPending(root)
            assertEquals(revision + 2, dirty.structureRevision)
            assertSame(secondChild, root.children[0])
            assertSame(firstChild, root.children[1])
            oldModifiers.forEachIndexed { index, entry ->
                assertSame(entry, firstChild.modifiers[index])
                assertSame(if (index == 0) root else oldModifiers[index - 1], entry.parent)
                assertSame(oldModifiers.getOrNull(index + 1) ?: firstChild, entry.virtualChild)
            }
            assertSame(oldModifiers.last(), firstChild.parent)
            assertSame(root, firstChild.logicalParent)
            assertEquals(0, probe.updates)
            assertEquals(24, probe.nodes.size)
        } finally {
            assertNull(lifecycle.cleanup(root))
            assertNull(reconciler.cleanupProvisionals())
        }
        assertEquals(24, probe.disposes)
        oldEntriesReleased(probe)
    }

    @Test
    fun identicalChildChainsKeepKeyedGeometryAndPropagateRealDirtyMasks() {
        val probe = ModifierProbe()
        val modifiers = chain(List(8) { CountingModifier(probe) })
        val components = TestProbe()
        val first = TestProbe.ProbeId("first")
        val second = TestProbe.ProbeId("second")
        val tree = UiTree()
        try {
            tree.update(components.root(listOf(components.element(first, first, modifier = modifiers), components.element(second, second, modifier = modifiers))))
            frame(tree)
            val node = components.nodeForTag(first)
            tree.update(components.root(listOf(components.element(second, second, modifier = modifiers), components.element(first, first, modifier = modifiers))))
            frame(tree)
            assertSame(node, components.nodeForTag(first))
            val visible = tree.semantics().drop(1)
            assertEquals(listOf(UiText.Literal("second"), UiText.Literal("first")), visible.map { it.semantics.label })
            components.inputEvents.clear()
            val bounds = visible[0].bounds
            assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset(bounds.left, bounds.top))))
            assertEquals(listOf(second), components.inputEvents)
            val measures = components.measureCalls
            val revision = tree.currentRevision()
            node.invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
            assertEquals(revision + 1, tree.currentRevision())
            frame(tree)
            assertEquals(measures + 2, components.measureCalls)
            assertEquals(0, probe.updates)
        } finally {
            tree.close()
        }
    }

    @Test
    fun oneSharedChainCreatesIndependentNodesAndCloseCannotAffectAnotherOwner() {
        val probe = ModifierProbe()
        val modifiers = chain(listOf(CountingModifier(probe)))
        val components = TestProbe()
        val first = UiTree()
        val second = UiTree()
        try {
            first.update(components.root(emptyList(), modifiers))
            second.update(components.root(emptyList(), modifiers))
            assertNotSame(probe.nodes[0], probe.nodes[1])
            frame(first)
            frame(second)
            val output = second.paint()
            first.close()
            assertEquals(1, probe.disposes)
            second.update(components.root(emptyList(), modifiers))
            assertEquals(output, second.paint())
            probe.nodes[1].invalidateForTest()
            second.paint()
        } finally {
            first.close()
            second.close()
        }
        assertEquals(2, probe.disposes)
        oldEntriesReleased(probe)
    }

    @Test
    fun identicalRootChainCannotBypassInvalidDescendants() {
        val probe = ModifierProbe()
        val modifiers = chain(listOf(CountingModifier(probe)))
        val components = TestProbe()
        val key = TestProbe.ProbeId("duplicate")
        val tree = UiTree()
        try {
            tree.update(components.root(emptyList(), modifiers))
            frame(tree)
            val paint = tree.paint()
            assertFailsWith<IllegalArgumentException> {
                tree.update(
                    components.root(
                        List(2) { components.element(key, key = key) },
                        modifiers,
                    ),
                )
            }
            assertEquals(TreeState.Active, tree.state)
            assertEquals(0, components.updateCalls)
            assertEquals(2, probe.validations)
            assertEquals(paint, tree.paint())
            tree.update(components.root(emptyList(), modifiers))
            assertEquals(1, components.updateCalls)
        } finally {
            tree.close()
        }
    }

    @Test
    fun componentAndChildFailuresAfterAdmissionPreservePrimaryFailureAndCleanup() {
        for (childFailure in listOf(false, true)) {
            val probe = ModifierProbe()
            val modifiers = chain(List(8) { CountingModifier(probe) })
            val components = TestProbe()
            val failure = IllegalStateException("component update")
            val child = TestProbe.ProbeId("child")
            val tree = UiTree()
            tree.update(components.root(listOf(components.element(child)), modifiers))
            val failingChild = components.element(child, onUpdate = { if (childFailure) throw failure })
            val description =
                components.element(
                    TestProbe.ProbeId("root"),
                    children = listOf(failingChild),
                    modifier = modifiers,
                    onUpdate = { if (childFailure.not()) throw failure },
                )
            assertSame(failure, assertFailsWith<IllegalStateException> { tree.update(description) })
            assertEquals(TreeState.Poisoned, tree.state)
            assertEquals(0, probe.updates)
            assertEquals(8, probe.detaches)
            assertEquals(8, probe.disposes)
            tree.close()
            assertEquals(8, probe.disposes)
            oldEntriesReleased(probe)
        }
    }

    @Test
    fun changedChainModifierFailureStillPoisonsAndReleasesEveryOwnedNode() {
        val probe = ModifierProbe()
        val components = TestProbe()
        val failure = IllegalStateException("modifier update")
        val tree = UiTree()
        tree.update(components.root(emptyList(), chain(List(8) { CountingModifier(probe) })))
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                tree.update(components.root(emptyList(), chain(List(8) { CountingModifier(probe, failure) })))
            },
        )
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(8, probe.detaches)
        assertEquals(8, probe.disposes)
        tree.close()
        assertEquals(8, probe.disposes)
        oldEntriesReleased(probe)
    }

    @Test
    fun identicalChainsPreserveDeferredCallbackReplacementAndSourceCutoff() {
        val probe = ModifierProbe()
        val modifiers = chain(List(8) { CountingModifier(probe) })
        val generation = mutableStateOf(0)
        val source = mutableStateOf(0)
        val stable = StateSource<Unit> { StateSubscription(StateSnapshot(StateRevision(0), Unit)) { } }
        val seen = ArrayList<Pair<Int, Int>>()
        createRuntimeUiSession {
            val parentValue = generation.value
            evaluateComponentTree {
                Stack(modifier = modifiers) {
                    Observe(stable) {
                        seen.add(parentValue to source.value)
                        Spacer()
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(8, 8))
            generation.value = 1
            source.value = 2
            val frame = session.frame(Constraints.fixed(8, 8))
            assertEquals(listOf(0 to 0, 1 to 2), seen)
            assertEquals(0, probe.updates)
            assertSame(frame, session.frame(Constraints.fixed(8, 8)))
        }
        assertEquals(8, probe.disposes)
    }

    private fun frame(tree: UiTree) {
        tree.measure(Constraints.fixed(8, 8))
        tree.layout()
        tree.paint()
        tree.semantics()
    }

    private fun chain(descriptions: List<CountingModifier>): Modifier = descriptions.fold(Modifier.Empty) { result, description -> result.then(description) }

    private fun oldEntriesReleased(probe: ModifierProbe) {
        for (node in probe.nodes) assertFailsWith<IllegalStateException> { node.invalidateForTest() }
    }

    /**
     * Invocation-local counters retaining only fixture-owned nodes for post-close invalidation checks.
     */
    private class ModifierProbe {
        var validations = 0
        var updates = 0
        var attaches = 0
        var detaches = 0
        var disposes = 0
        val nodes = ArrayList<CountingNode>()
    }

    /**
     * Immutable pass-through description with observable typed validation and update hooks.
     */
    private class CountingModifier(
        val probe: ModifierProbe,
        val updateFailure: Throwable? = null,
    ) : ModifierElement {
        override val type: ModifierNodeType<CountingModifier, CountingNode>
            get() = TYPE

        companion object {
            val TYPE =
                ModifierNodeType(
                    elementClass = CountingModifier::class,
                    nodeClass = CountingNode::class,
                    validateLocal = { it.probe.validations += 1 },
                    createNode = { description -> CountingNode(description.probe).also { description.probe.nodes.add(it) } },
                    updateNode = { _, current, _ ->
                        current.updateFailure?.let { throw it }
                        current.probe.updates += 1
                        DirtyMask.None
                    },
                )
        }
    }

    /**
     * Owner-isolated active node whose inherited geometry forwards its one virtual child.
     */
    private class CountingNode(
        private val probe: ModifierProbe,
    ) : ModifierNode(),
        LifecycleNode {
        override fun attach() {
            probe.attaches += 1
        }

        override fun detach() {
            probe.detaches += 1
        }

        override fun dispose() {
            probe.disposes += 1
        }

        /**
         * Exercises the retained binding and its terminal invalidation rejection.
         */
        fun invalidateForTest() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }
}
