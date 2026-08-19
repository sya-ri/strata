package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies lifecycle ownership, rollback, failure accumulation, and operation reentrancy.
 */
internal class LifecycleFailureTest {
    @Test
    fun partialCreateFailureDisposesOnlyOwnedDetachedNodes() {
        val createFailure = IllegalStateException("create failed")
        val probe = TestProbe(failingCreateTag = id("failed"), createFailure = createFailure)
        val tree = UiTree()

        assertSame(
            createFailure,
            assertThrows(IllegalStateException::class.java) {
                tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("failed")))))
            },
        )

        assertEquals(
            listOf(TestProbe.Event.Dispose(id("first")), TestProbe.Event.Dispose(id("root"))),
            probe.events,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun attachFailureCleansReachedNodesAndUnreachedNodesWithoutDetach() {
        val attachFailure = IllegalStateException("attach failed")
        val probe = TestProbe(failingAttachTag = id("middle"), attachFailure = attachFailure)
        val tree = UiTree()

        assertSame(
            attachFailure,
            assertThrows(IllegalStateException::class.java) {
                tree.update(
                    probe.root(
                        listOf(probe.element(id("first")), probe.element(id("middle")), probe.element(id("last"))),
                    ),
                )
            },
        )

        assertEquals(
            listOf(
                TestProbe.Event.Attach(id("root")),
                TestProbe.Event.Attach(id("first")),
                TestProbe.Event.Attach(id("middle")),
                TestProbe.Event.Dispose(id("last")),
                TestProbe.Event.Detach(id("middle")),
                TestProbe.Event.Dispose(id("middle")),
                TestProbe.Event.Detach(id("first")),
                TestProbe.Event.Dispose(id("first")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun rootAttachFailureDisposesUnreachedDescendantsWithoutDetach() {
        val attachFailure = IllegalStateException("root attach failed")
        val probe = TestProbe(failingAttachTag = id("root"), attachFailure = attachFailure)
        val tree = UiTree()

        assertSame(
            attachFailure,
            assertThrows(IllegalStateException::class.java) {
                tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("last")))))
            },
        )
        assertEquals(
            listOf(
                TestProbe.Event.Attach(id("root")),
                TestProbe.Event.Dispose(id("last")),
                TestProbe.Event.Dispose(id("first")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun leafAttachFailureDetachesReachedAncestorsAndDisposesUnreachedSiblings() {
        val attachFailure = IllegalStateException("leaf attach failed")
        val probe = TestProbe(failingAttachTag = id("leaf"), attachFailure = attachFailure)
        val tree = UiTree()

        assertSame(
            attachFailure,
            assertThrows(IllegalStateException::class.java) {
                tree.update(
                    probe.root(
                        listOf(probe.element(id("first")), probe.element(id("leaf")), probe.element(id("last"))),
                    ),
                )
            },
        )
        assertEquals(
            listOf(
                TestProbe.Event.Attach(id("root")),
                TestProbe.Event.Attach(id("first")),
                TestProbe.Event.Attach(id("leaf")),
                TestProbe.Event.Dispose(id("last")),
                TestProbe.Event.Detach(id("leaf")),
                TestProbe.Event.Dispose(id("leaf")),
                TestProbe.Event.Detach(id("first")),
                TestProbe.Event.Dispose(id("first")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun cleanupUsesReverseSiblingAndDescendantFirstOrder() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(
            probe.root(
                listOf(
                    probe.element(
                        id("parent-a"),
                        children = listOf(probe.element(id("leaf-a")), probe.element(id("leaf-b"))),
                    ),
                    probe.element(id("parent-b")),
                ),
            ),
        )
        probe.events.clear()

        tree.close()

        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("parent-b")),
                TestProbe.Event.Dispose(id("parent-b")),
                TestProbe.Event.Detach(id("leaf-b")),
                TestProbe.Event.Dispose(id("leaf-b")),
                TestProbe.Event.Detach(id("leaf-a")),
                TestProbe.Event.Dispose(id("leaf-a")),
                TestProbe.Event.Detach(id("parent-a")),
                TestProbe.Event.Dispose(id("parent-a")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
    }

    @Test
    fun updateFailurePoisonsAndCleansInstalledOwnership() {
        val updateFailure = IllegalStateException("update failed")
        val probe = TestProbe(failingUpdateTag = id("new"), updateFailure = updateFailure)
        val tree = UiTree()
        tree.update(probe.root(emptyList()))
        probe.events.clear()

        assertSame(
            updateFailure,
            assertThrows(IllegalStateException::class.java) {
                tree.update(probe.element(id("new")))
            },
        )

        assertEquals(
            listOf(TestProbe.Event.Detach(id("root")), TestProbe.Event.Dispose(id("root"))),
            probe.events,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun replacementCleanupFailureCleansDetachedReplacementAndPreservesSuppressionIdentity() {
        val oldDetachFailure = IllegalStateException("old detach")
        val replacementDisposeFailure = IllegalStateException("new dispose")
        val probe =
            TestProbe(
                failingDetachTag = id("old"),
                failingDisposeTag = id("new"),
                detachFailure = oldDetachFailure,
                disposeFailure = replacementDisposeFailure,
            )
        val tree = UiTree()
        tree.update(probe.element(id("old"), id("old")))
        probe.events.clear()

        val failure =
            assertThrows(IllegalStateException::class.java) {
                tree.update(probe.element(id("new"), id("new")))
            }

        assertSame(oldDetachFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertSame(replacementDisposeFailure, failure.suppressed.single())
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Dispose(id("new")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun childReplacementCleanupFailurePreservesPrimaryAndSuppressionOrder() {
        val oldDetachFailure = IllegalStateException("old child detach")
        val firstReplacementDisposeFailure = IllegalStateException("first replacement dispose")
        val secondReplacementDisposeFailure = IllegalStateException("second replacement dispose")
        val probe = TestProbe(failingDetachTag = id("old"), detachFailure = oldDetachFailure)
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("old"), id("old")))))
        probe.events.clear()

        val replacement =
            probe.root(
                listOf(
                    probe.element(
                        id("new"),
                        id("new"),
                        children =
                            listOf(
                                probe.element(id("new-first"), onDispose = { throw firstReplacementDisposeFailure }),
                                probe.element(id("new-second"), onDispose = { throw secondReplacementDisposeFailure }),
                            ),
                    ),
                ),
            )
        val failure = assertThrows(IllegalStateException::class.java) { tree.update(replacement) }

        assertSame(oldDetachFailure, failure)
        assertEquals(2, failure.suppressed.size)
        assertSame(secondReplacementDisposeFailure, failure.suppressed[0])
        assertSame(firstReplacementDisposeFailure, failure.suppressed[1])
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Dispose(id("new-second")),
                TestProbe.Event.Dispose(id("new-first")),
                TestProbe.Event.Dispose(id("new")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        val events = probe.events.toList()
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun updateFailureCleansProvisionalChildAndInstalledOwnershipOnce() {
        val updateFailure = IllegalStateException("existing update")
        val provisionalDisposeFailure = IllegalStateException("provisional dispose")
        val probe =
            TestProbe(
                failingUpdateTag = id("existing-updated"),
                failingDisposeTag = id("provisional"),
                updateFailure = updateFailure,
                disposeFailure = provisionalDisposeFailure,
            )
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("existing"), id("existing")))))
        probe.events.clear()

        val incoming =
            probe.root(
                listOf(
                    probe.element(id("provisional"), id("provisional")),
                    probe.element(id("existing-updated"), id("existing")),
                ),
            )
        val failure = assertThrows(IllegalStateException::class.java) { tree.update(incoming) }

        assertSame(updateFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertSame(provisionalDisposeFailure, failure.suppressed.single())
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("existing")),
                TestProbe.Event.Dispose(id("existing")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
                TestProbe.Event.Dispose(id("provisional")),
            ),
            probe.events,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        val events = probe.events.toList()
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun sameThrowableIsNeverSelfSuppressedAndCallbacksRunOnce() {
        val sameFailure = IllegalStateException("same failure")
        val probe =
            TestProbe(
                failingDetachTag = id("old"),
                failingDisposeTag = id("old"),
                detachFailure = sameFailure,
                disposeFailure = sameFailure,
            )
        val tree = UiTree()
        tree.update(probe.element(id("old"), id("old")))

        val failure =
            assertThrows(IllegalStateException::class.java) {
                tree.update(probe.element(id("new"), id("new")))
            }

        assertSame(sameFailure, failure)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Dispose(id("new")),
            ),
            probe.events.drop(1),
        )
        tree.close()
    }

    @Test
    fun closeFailureIsTerminalAndDoesNotRepeatCallbacks() {
        val detachFailure = IllegalStateException("close detach")
        val disposeFailure = IllegalStateException("close dispose")
        val probe =
            TestProbe(
                failingDetachTag = id("root"),
                failingDisposeTag = id("root"),
                detachFailure = detachFailure,
                disposeFailure = disposeFailure,
            )
        val tree = UiTree()
        tree.update(probe.root(emptyList()))

        val failure = assertThrows(IllegalStateException::class.java) { tree.close() }
        val events = probe.events.toList()
        assertSame(detachFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertSame(disposeFailure, failure.suppressed.single())
        assertEquals(TreeState.Closed, tree.state)
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun sameNodeTwiceInOneProvisionalSubtreeIsDisposedOnce() {
        val probe = TestProbe()
        val shared = TestProbe.ProbeNode(probe, id("shared"))
        val tree = UiTree()

        assertThrows(IllegalStateException::class.java) {
            tree.update(
                probe.root(
                    listOf(
                        probe.element(id("first"), sharedNode = shared),
                        probe.element(id("second"), sharedNode = shared),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(TestProbe.Event.Dispose(id("shared")), TestProbe.Event.Dispose(id("root"))),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun aliasAttemptDuringDetachCannotClaimTheLiveNode() {
        val probe = TestProbe()
        val shared = TestProbe.ProbeNode(probe, id("shared"))
        val owner = UiTree()
        owner.update(probe.element(id("shared"), sharedNode = shared))
        val other = UiTree()
        val attempts = ArrayList<Throwable>()
        shared.onDetach = {
            runCatching { other.update(probe.element(id("alias"), sharedNode = shared)) }
                .exceptionOrNull()
                ?.let(attempts::add)
        }

        owner.close()

        assertEquals(1, attempts.size)
        assertTrue(attempts.single() is IllegalStateException)
        assertEquals(TreeState.Poisoned, other.state)
        other.close()
    }

    @Test
    fun aliasAttemptDuringDisposeCannotClaimTheLiveNode() {
        val probe = TestProbe()
        val shared = TestProbe.ProbeNode(probe, id("shared"))
        val owner = UiTree()
        owner.update(probe.element(id("shared"), sharedNode = shared))
        val other = UiTree()
        val attempts = ArrayList<Throwable>()
        shared.onDispose = {
            runCatching { other.update(probe.element(id("alias"), sharedNode = shared)) }
                .exceptionOrNull()
                ?.let(attempts::add)
        }

        owner.close()

        assertEquals(1, attempts.size)
        assertEquals(TreeState.Closed, owner.state)
        assertEquals(TreeState.Poisoned, other.state)
        other.close()
    }

    @Test
    fun cleanupPremarksSiblingsBeforeDetachCallbacks() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("second")))))
        val first = probe.nodeForTag(id("first"))
        val second = probe.nodeForTag(id("second"))
        second.onDetach = { first.invalidateForTest(DirtyMask.of(DirtyPhase.Paint)) }
        probe.events.clear()

        val failure = assertThrows(IllegalStateException::class.java) { tree.close() }

        assertEquals("Node invalidation is unavailable during cleanup.", failure.message)
        assertEquals(TreeState.Closed, tree.state)
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("second")),
                TestProbe.Event.Dispose(id("second")),
                TestProbe.Event.Detach(id("first")),
                TestProbe.Event.Dispose(id("first")),
                TestProbe.Event.Detach(id("root")),
                TestProbe.Event.Dispose(id("root")),
            ),
            probe.events,
        )
        val events = probe.events.toList()
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun cleanupRejectsSelfInvalidationDuringDispose() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(emptyList()))
        val root = probe.nodeForTag(id("root"))
        root.onDispose = { root.invalidateForTest(DirtyMask.of(DirtyPhase.Paint)) }
        probe.events.clear()

        val failure = assertThrows(IllegalStateException::class.java) { tree.close() }

        assertEquals("Node invalidation is unavailable during cleanup.", failure.message)
        assertEquals(TreeState.Closed, tree.state)
        assertEquals(
            listOf(TestProbe.Event.Detach(id("root")), TestProbe.Event.Dispose(id("root"))),
            probe.events,
        )
        val events = probe.events.toList()
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun validationReentrancyLeavesTreeActiveForRetry() {
        val tree = UiTree()
        val element = ValidationElement { tree.update(ValidationElement()) }

        assertThrows(IllegalStateException::class.java) { tree.update(element) }
        assertEquals(TreeState.Active, tree.state)
        tree.close()
    }

    @Test
    fun reentrantUpdateAndCloseAreRejected() {
        val updateProbe = TestProbe()
        val updateTree = UiTree()
        updateTree.update(updateProbe.root(emptyList()))
        val updateElement = updateProbe.element(id("updated"), onUpdate = { updateTree.update(updateProbe.root(emptyList())) })

        assertThrows(IllegalStateException::class.java) { updateTree.update(updateElement) }
        assertEquals(TreeState.Poisoned, updateTree.state)
        updateTree.close()

        val closeProbe = TestProbe()
        val closeTree = UiTree()
        val closeElement = closeProbe.element(id("close"), onAttach = { closeTree.close() })

        assertThrows(IllegalStateException::class.java) { closeTree.update(closeElement) }
        assertEquals(TreeState.Poisoned, closeTree.state)
        closeTree.close()
    }

    private fun id(value: String): TestProbe.ProbeId = TestProbe.ProbeId(value)

    /**
     * Element whose local validation callback attempts reentrant mutation.
     */
    private class ValidationElement(
        private val onValidate: (() -> Unit)? = null,
    ) : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable token for the validation fixture.
         */
        companion object {
            val TYPE: ElementType<ValidationElement, ValidationNode> =
                ElementType(
                    elementClass = ValidationElement::class,
                    nodeClass = ValidationNode::class,
                    validateLocal = { current -> current.onValidate?.invoke() },
                    createNode = { ValidationNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Node created by the validation reentrancy fixture.
     */
    private class ValidationNode : Node()
}
