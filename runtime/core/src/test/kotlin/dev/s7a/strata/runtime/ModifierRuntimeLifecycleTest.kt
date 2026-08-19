package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies independent modifier lifecycle ownership, cleanup failures, aliases, and live edit ordering.
 */
internal class ModifierRuntimeLifecycleTest {
    @Test
    fun liveModifierChangesCleanBeforeAttachAndRetainComponentLifecycle() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 1, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 2, ModifierTestFixture.Kind.Second)
        val third = modifierFixture.modifier(modifierProbe, 3, ModifierTestFixture.Kind.Third)

        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
        modifierProbe.events.clear()
        componentProbe.events.clear()

        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second, third)))
        assertEquals(listOf(ModifierTestFixture.Event.Attach(3)), modifierProbe.events)
        assertEquals(emptyList<TestProbe.Event>(), componentProbe.events)

        modifierProbe.events.clear()
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(3),
                ModifierTestFixture.Event.Dispose(3),
            ),
            modifierProbe.events,
        )

        modifierProbe.events.clear()
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(second, third)))
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(2),
                ModifierTestFixture.Event.Dispose(2),
                ModifierTestFixture.Event.Detach(1),
                ModifierTestFixture.Event.Dispose(1),
                ModifierTestFixture.Event.Attach(2),
                ModifierTestFixture.Event.Attach(3),
            ),
            modifierProbe.events,
        )
        modifierProbe.events.clear()
        tree.close()
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(3),
                ModifierTestFixture.Event.Dispose(3),
                ModifierTestFixture.Event.Detach(2),
                ModifierTestFixture.Event.Dispose(2),
            ),
            modifierProbe.events,
        )
        assertEquals(
            listOf(
                TestProbe.Event.Detach(TestProbe.ProbeId("root")),
                TestProbe.Event.Dispose(TestProbe.ProbeId("root")),
            ),
            componentProbe.events,
        )
    }

    @Test
    fun attachFailureContinuesReverseCleanupExactlyOnce() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val failure = IllegalStateException("modifier attach")
        modifierProbe.attachFailures[80] = failure
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 80, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 81, ModifierTestFixture.Kind.Second)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
            }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Attach(80),
                ModifierTestFixture.Event.Dispose(81),
                ModifierTestFixture.Event.Detach(80),
                ModifierTestFixture.Event.Dispose(80),
            ),
            modifierProbe.events,
        )
        tree.close()
        assertEquals(1, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Detach })
        assertEquals(2, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Dispose })
    }

    @Test
    fun liveRemovalDetachFailureContinuesDisposeExactlyOnce() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val failure = IllegalStateException("modifier detach")
        modifierProbe.detachFailures[83] = failure
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 82, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 83, ModifierTestFixture.Kind.Second)
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
        modifierProbe.events.clear()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty))
            }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(83),
                ModifierTestFixture.Event.Dispose(83),
                ModifierTestFixture.Event.Detach(82),
                ModifierTestFixture.Event.Dispose(82),
            ),
            modifierProbe.events,
        )
        tree.close()
        assertEquals(2, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Detach })
        assertEquals(2, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Dispose })
    }

    @Test
    fun initialAndFinalLifecycleOrderSpansModifiersAndDescendant() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val order = ArrayList<ModifierTestFixture.LifecycleObservation>()
        modifierProbe.onEvent = { event -> order += ModifierTestFixture.LifecycleObservation.Modifier(event) }
        val tree = UiTree()
        val outerModifierId = 61
        val innerModifierId = 62
        val childModifierId = 63
        val first = modifierFixture.modifier(modifierProbe, outerModifierId, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, innerModifierId, ModifierTestFixture.Kind.Second)
        val descendantModifier = modifierFixture.modifier(modifierProbe, childModifierId, ModifierTestFixture.Kind.Third)
        val rootId = TestProbe.ProbeId("ordered-root")
        val childId = TestProbe.ProbeId("ordered-child")
        val component =
            componentProbe.element(
                rootId,
                modifier = modifierFixture.chain(first, second),
                children =
                    listOf(
                        componentProbe.element(
                            childId,
                            modifier = Modifier.Empty.then(descendantModifier),
                            onAttach = {
                                order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Attach(childId))
                            },
                            onDetach = {
                                order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Detach(childId))
                            },
                            onDispose = {
                                order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Dispose(childId))
                            },
                        ),
                    ),
                onAttach = {
                    order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Attach(rootId))
                },
                onDetach = {
                    order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Detach(rootId))
                },
                onDispose = {
                    order += ModifierTestFixture.LifecycleObservation.Component(TestProbe.Event.Dispose(rootId))
                },
            )

        tree.update(component)
        tree.close()
        assertEquals(
            modifierFixture.expectedTreeLifecycle(
                rootId,
                childId,
                outerModifierId,
                innerModifierId,
                childModifierId,
            ),
            order,
        )
    }

    @Test
    fun retainedGapLiveEditCleansRemovedSiblingBeforeAttachingNewSibling() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 90, ModifierTestFixture.Kind.First)
        val removed = modifierFixture.modifier(modifierProbe, 91, ModifierTestFixture.Kind.Second)
        val retained = modifierFixture.modifier(modifierProbe, 92, ModifierTestFixture.Kind.Third)
        val added = modifierFixture.modifier(modifierProbe, 93, ModifierTestFixture.Kind.First)
        val firstKey = TestProbe.ProbeId("gap-first")
        val removedKey = TestProbe.ProbeId("gap-removed")
        val retainedKey = TestProbe.ProbeId("gap-retained")
        val addedKey = TestProbe.ProbeId("gap-added")

        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(TestProbe.ProbeId("first"), firstKey, modifier = Modifier.Empty.then(first)),
                    componentProbe.element(TestProbe.ProbeId("removed"), removedKey, modifier = Modifier.Empty.then(removed)),
                    componentProbe.element(TestProbe.ProbeId("retained"), retainedKey, modifier = Modifier.Empty.then(retained)),
                ),
            ),
        )
        modifierProbe.events.clear()
        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(TestProbe.ProbeId("first"), firstKey, modifier = Modifier.Empty.then(first)),
                    componentProbe.element(TestProbe.ProbeId("retained"), retainedKey, modifier = Modifier.Empty.then(retained)),
                    componentProbe.element(TestProbe.ProbeId("added"), addedKey, modifier = Modifier.Empty.then(added)),
                ),
            ),
        )
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(91),
                ModifierTestFixture.Event.Dispose(91),
                ModifierTestFixture.Event.Attach(93),
            ),
            modifierProbe.events,
        )
        tree.close()
    }

    @Test
    fun retainedModifierGapCleansReplacedEntryBeforeAttachingNewEntry() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val outer = modifierFixture.modifier(modifierProbe, 94, ModifierTestFixture.Kind.First)
        val removed = modifierFixture.modifier(modifierProbe, 95, ModifierTestFixture.Kind.Second)
        val inner = modifierFixture.modifier(modifierProbe, 96, ModifierTestFixture.Kind.Third)
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(outer, removed, inner)))
        val outerNode = modifierProbe.nodes.getValue(94)
        val innerNode = modifierProbe.nodes.getValue(96)
        modifierProbe.events.clear()

        val added = modifierFixture.modifier(modifierProbe, 97, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(outer, added, inner)))

        assertSame(outerNode, modifierProbe.nodes.getValue(94))
        assertSame(innerNode, modifierProbe.nodes.getValue(96))
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Detach(95),
                ModifierTestFixture.Event.Dispose(95),
                ModifierTestFixture.Event.Attach(97),
            ),
            modifierProbe.events,
        )
        tree.close()
    }

    @Test
    fun createFailureCleansEarlierOwnedEntriesWithoutChangingPrimary() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 84, ModifierTestFixture.Kind.First)
        val failure = IllegalStateException("modifier create")
        val failing =
            modifierFixture.modifier(
                modifierProbe,
                85,
                ModifierTestFixture.Kind.Second,
                createFailure = failure,
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, failing)))
            }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(84)), modifierProbe.events)
        assertEquals(
            listOf(TestProbe.Event.Dispose(TestProbe.ProbeId("root"))),
            componentProbe.events,
        )
        val firstNode = modifierProbe.nodes.getValue(84)
        assertThrows(IllegalStateException::class.java) { firstNode.invalidate(DirtyPhase.Paint) }
        tree.close()
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(84)), modifierProbe.events)
    }

    @Test
    fun liveCreateFailureCleansNewProvisionalAndInstalledOwnership() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val installed = modifierFixture.modifier(modifierProbe, 86, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(installed)))
        modifierProbe.events.clear()
        componentProbe.events.clear()
        val provisional = modifierFixture.modifier(modifierProbe, 87, ModifierTestFixture.Kind.Second)
        val failure = IllegalStateException("live modifier create")
        val failing =
            modifierFixture.modifier(
                modifierProbe,
                88,
                ModifierTestFixture.Kind.Third,
                createFailure = failure,
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(provisional, failing)))
            }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ModifierTestFixture.Event.Dispose(87),
                ModifierTestFixture.Event.Detach(86),
                ModifierTestFixture.Event.Dispose(86),
            ),
            modifierProbe.events,
        )
        assertEquals(
            listOf(
                TestProbe.Event.Detach(TestProbe.ProbeId("root")),
                TestProbe.Event.Dispose(TestProbe.ProbeId("root")),
            ),
            componentProbe.events,
        )
        assertThrows(IllegalStateException::class.java) {
            modifierProbe.nodes.getValue(87).invalidate(DirtyPhase.Paint)
        }
        tree.close()
        assertEquals(2, modifierProbe.events.count { event -> event is ModifierTestFixture.Event.Dispose })
    }

    @Test
    fun componentAliasIsRejectedAcrossTreesAndWithinOneTree() {
        val probe = TestProbe()
        val sharedNode =
            TestProbe.ProbeNode(
                probe,
                TestProbe.ProbeId("shared-component"),
            )
        val owner = UiTree()
        val owned = probe.element(TestProbe.ProbeId("shared-component"), sharedNode = sharedNode)
        owner.update(owned)
        val aliased = probe.element(TestProbe.ProbeId("shared-component"), sharedNode = sharedNode)
        val other = UiTree()
        assertThrows(IllegalStateException::class.java) { other.update(aliased) }
        assertEquals(TreeState.Active, owner.state)
        other.close()
        owner.close()

        val withinTree = UiTree()
        val firstSharedNode =
            TestProbe.ProbeNode(
                probe,
                TestProbe.ProbeId("same-tree-shared-component"),
            )
        val first = probe.element(TestProbe.ProbeId("same-tree-first"), sharedNode = firstSharedNode)
        val second = probe.element(TestProbe.ProbeId("same-tree-second"), sharedNode = firstSharedNode)
        assertThrows(IllegalStateException::class.java) {
            withinTree.update(probe.root(listOf(first, second)))
        }
        withinTree.close()
    }

    @Test
    fun modifierNodeOwnershipAndInvalidationFollowCleanup() {
        val modifierFixture = ModifierTestFixture()
        val probe = ModifierTestFixture.Probe()
        val shared =
            ModifierTestFixture.TestModifierNode(
                probe,
                50,
                ModifierTestFixture.Kind.First,
                ModifierTestFixture.Behavior.PassThrough,
            )
        val owner = UiTree()
        val ownerModifier =
            modifierFixture.modifier(
                probe,
                50,
                ModifierTestFixture.Kind.First,
                sharedNode = shared,
            )
        owner.update(TestProbe().root(emptyList(), modifier = Modifier.Empty.then(ownerModifier)))
        val other = UiTree()
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                other.update(TestProbe().root(emptyList(), modifier = Modifier.Empty.then(ownerModifier)))
            }
        assertTrue(thrown.message.orEmpty().contains("already"))
        assertEquals(TreeState.Active, owner.state)
        other.close()

        shared.invalidate(DirtyPhase.Paint)
        owner.close()
        assertThrows(IllegalStateException::class.java) { shared.invalidate(DirtyPhase.Paint) }

        val retiredOwner = UiTree()
        assertThrows(IllegalStateException::class.java) {
            retiredOwner.update(TestProbe().root(emptyList(), modifier = Modifier.Empty.then(ownerModifier)))
        }
        assertEquals(TreeState.Poisoned, retiredOwner.state)
        retiredOwner.close()
    }

    @Test
    fun invalidationDuringModifierDetachAndDisposeIsRejected() {
        val modifierFixture = ModifierTestFixture()
        val probe = ModifierTestFixture.Probe()
        val tree = UiTree()
        var node: ModifierTestFixture.TestModifierNode? = null
        var rejectedInvalidations = 0
        val disposeFailure = IllegalStateException("modifier dispose")
        probe.disposeFailure = disposeFailure
        probe.onEvent = { event ->
            if (event is ModifierTestFixture.Event.Detach || event is ModifierTestFixture.Event.Dispose) {
                try {
                    node?.invalidate(DirtyPhase.Paint)
                } catch (_: IllegalStateException) {
                    rejectedInvalidations += 1
                }
            }
        }
        val element = modifierFixture.modifier(probe, 60, ModifierTestFixture.Kind.First)
        tree.update(TestProbe().root(emptyList(), modifier = Modifier.Empty.then(element)))
        node = probe.nodes.getValue(60)
        val retainedNode = checkNotNull(node)

        val thrown = assertThrows(IllegalStateException::class.java) { tree.close() }
        assertSame(disposeFailure, thrown)
        assertEquals(TreeState.Closed, tree.state)
        assertEquals(2, rejectedInvalidations)
        assertThrows(IllegalStateException::class.java) { retainedNode.invalidate(DirtyPhase.Paint) }
    }

    @Test
    fun finalCloseContinuesModifierFailuresInDeterministicOrder() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val rootDetach = IllegalStateException("root detach")
        val rootDispose = IllegalStateException("root dispose")
        val firstDetach = IllegalStateException("first detach")
        val secondDispose = IllegalStateException("second dispose")
        modifierProbe.detachFailures[70] = firstDetach
        modifierProbe.disposeFailures[71] = secondDispose
        val root =
            componentProbe.element(
                TestProbe.ProbeId("failure-root"),
                modifier =
                    modifierFixture.chain(
                        modifierFixture.modifier(modifierProbe, 70, ModifierTestFixture.Kind.First),
                        modifierFixture.modifier(modifierProbe, 71, ModifierTestFixture.Kind.Second),
                    ),
                onDetach = { throw rootDetach },
                onDispose = { throw rootDispose },
            )
        val tree = UiTree()
        tree.update(root)

        val thrown = assertThrows(IllegalStateException::class.java) { tree.close() }
        assertSame(rootDetach, thrown)
        assertEquals(
            listOf(rootDispose, secondDispose, firstDetach),
            thrown.suppressed.toList(),
        )
        assertEquals(TreeState.Closed, tree.state)
        val events = modifierProbe.events.toList()
        tree.close()
        assertEquals(events, modifierProbe.events)
        assertEquals(1, events.count { event -> event is ModifierTestFixture.Event.Detach && event.id == 70 })
        assertEquals(1, events.count { event -> event is ModifierTestFixture.Event.Dispose && event.id == 71 })
    }

    @Test
    fun sameTreeDuplicateModifierNodeAliasIsRejectedAndDisposedOnce() {
        val modifierFixture = ModifierTestFixture()
        val probe = ModifierTestFixture.Probe()
        val shared =
            ModifierTestFixture.TestModifierNode(
                probe,
                72,
                ModifierTestFixture.Kind.First,
                ModifierTestFixture.Behavior.PassThrough,
            )
        val element =
            modifierFixture.modifier(
                probe,
                72,
                ModifierTestFixture.Kind.First,
                sharedNode = shared,
            )
        val tree = UiTree()
        assertThrows(IllegalStateException::class.java) {
            tree.update(TestProbe().root(emptyList(), modifier = modifierFixture.chain(element, element)))
        }
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(72)), probe.events)
        tree.close()
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(72)), probe.events)
    }

    @Test
    fun componentAndModifierCannotClaimTheSameNodeInstance() {
        val modifierFixture = ModifierTestFixture()
        val probe = ModifierTestFixture.Probe()
        val shared =
            ModifierTestFixture.TestModifierNode(
                probe,
                73,
                ModifierTestFixture.Kind.First,
                ModifierTestFixture.Behavior.PassThrough,
            )
        val modifier =
            modifierFixture.modifier(
                probe,
                73,
                ModifierTestFixture.Kind.First,
                sharedNode = shared,
            )
        val root = CrossCategoryElement(shared, Modifier.Empty.then(modifier))
        val tree = UiTree()

        val thrown = assertThrows(IllegalStateException::class.java) { tree.update(root) }
        assertEquals("The node instance is already runtime-owned by this tree.", thrown.message)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(listOf(ModifierTestFixture.Event.Dispose(73)), probe.events)
        assertThrows(IllegalStateException::class.java) { shared.invalidate(DirtyPhase.Paint) }
        tree.close()
        assertEquals(1, probe.events.count { event -> event is ModifierTestFixture.Event.Dispose })
    }

    private class CrossCategoryElement(
        val sharedNode: ModifierTestFixture.TestModifierNode,
        modifier: Modifier,
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
            modifier = modifier,
        ) {
        companion object {
            val TYPE: ElementType<CrossCategoryElement, ModifierTestFixture.TestModifierNode> =
                ElementType(
                    elementClass = CrossCategoryElement::class,
                    nodeClass = ModifierTestFixture.TestModifierNode::class,
                    validateLocal = { },
                    createNode = CrossCategoryElement::sharedNode,
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
