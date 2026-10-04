package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameCutoffNode
import dev.s7a.strata.node.FrameTimeNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies current-tree callback membership, ordering, identity reuse and terminal release.
 */
internal class FrameCallbackIndexTest {
    @Test
    fun effectiveModifierAncestryCapturesAllBeforeCommitAndPreservesRepeatedTimes() {
        val log = ArrayList<String>()
        val root = retained("root", log)
        root.children.add(retained("child", log))
        val fixture = ModifierTestFixture()
        val modifier =
            RetainedModifier(
                fixture.modifier(ModifierTestFixture.Probe(), 1, ModifierTestFixture.Kind.First),
                CallbackNode("modifier", log),
            )
        root.modifiers.add(modifier)
        modifier.virtualChild = root
        val index = FrameCallbackIndex()
        index.capture(root, 0)
        index.commit(root, 0)
        repeat(2) { index.advance(root, 0, FrameTime(7)) }
        assertEquals(
            listOf("capture modifier", "capture root", "capture child", "commit modifier", "commit root", "commit child") +
                List(2) { listOf("time modifier 7", "time root 7", "time child 7") }.flatten(),
            log,
        )
    }

    @Test
    fun unchangedTopologyReusesListsAndRevisionRebuildsForReorderedAddedAndRemovedChildren() {
        val log = ArrayList<String>()
        val root = retained("root", log)
        val first = retained("first", log)
        val second = retained("second", log)
        root.children.addAll(listOf(first, second))
        val index = FrameCallbackIndex()
        index.capture(root, 0)
        val cutoff = field(index, "cutoff")
        val timed = field(index, "timed")
        repeat(3) {
            index.capture(root, 0)
            index.commit(root, 0)
            index.advance(root, 0, FrameTime(7))
        }
        assertSame(cutoff, field(index, "cutoff"))
        assertSame(timed, field(index, "timed"))
        root.children.clear()
        root.children.addAll(listOf(second, retained("third", log)))
        log.clear()
        index.capture(root, 1)
        index.commit(root, 1)
        assertEquals(listOf("capture root", "capture second", "capture third", "commit root", "commit second", "commit third"), log)
        index.clear()
        assertNull(field(index, "root"))
        assertEquals(emptyList<Any>(), field(index, "cutoff"))
        assertEquals(emptyList<Any>(), field(index, "timed"))
        val replacement = retained("replacement", log)
        log.clear()
        index.capture(replacement, 1)
        assertEquals(listOf("capture replacement"), log)
    }

    @Test
    fun treeReconciliationTracksKeyedReorderingAndRootReplacementDespiteEqualPhaseMasks() {
        val log = ArrayList<String>()
        val tree = UiTree()
        val first = CallbackElement("first", log)
        val second = CallbackElement("second", log)
        tree.update(CallbackElement("root", log, listOf(first, second)))
        tree.captureFrameState()
        tree.commitFrameState()
        log.clear()
        tree.update(CallbackElement("root", log, listOf(second, first)))
        tree.captureFrameState()
        tree.commitFrameState()
        assertEquals(listOf("capture root", "capture second", "capture first", "commit root", "commit second", "commit first"), log)
        tree.update(TestProbe().root(emptyList()))
        log.clear()
        tree.captureFrameState()
        tree.commitFrameState()
        tree.advanceFrame(FrameTime(7))
        assertEquals(emptyList<String>(), log)
        tree.close()
    }

    @Test
    fun phaseInvalidationKeepsStructuralTokenWhileStructuralWorkAdvancesIt() {
        val tracker = DirtyTracker()
        val root = retained("root", ArrayList())
        tracker.record(root, DirtyMask.of(DirtyPhase.Measure))
        assertEquals(0L, tracker.structureRevision)
        tracker.structural(root)
        assertEquals(1L, tracker.structureRevision)
    }

    @Test
    fun callbackFailurePoisonsTreeAndClearsIndexBeforeLifecycleCleanup() {
        for (operation in listOf("capture", "commit", "time")) {
            val log = ArrayList<String>()
            val failure = IllegalArgumentException(operation)
            val tree = UiTree()
            val pipeline = field(tree, "pipeline")
            val index = field(checkNotNull(pipeline), "frameCallbacks")
            tree.update(
                CallbackElement("root", log, failure = failure, failureOperation = operation, onDispose = {
                    assertNull(field(checkNotNull(index), "root"))
                    assertEquals(emptyList<Any>(), field(index, "cutoff"))
                    assertEquals(emptyList<Any>(), field(index, "timed"))
                }),
            )
            val action = {
                tree.captureFrameState()
                tree.commitFrameState()
                tree.advanceFrame(FrameTime(7))
            }
            assertSame(failure, assertThrows(IllegalArgumentException::class.java, action))
            assertEquals(TreeState.Poisoned, tree.state)
            assertNull(field(checkNotNull(index), "root"))
            assertEquals(emptyList<Any>(), field(index, "cutoff"))
            assertEquals(emptyList<Any>(), field(index, "timed"))
            assertEquals("dispose root", log.last())
            tree.close()
        }
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private fun retained(
        name: String,
        log: MutableList<String>,
    ): RetainedNode {
        val element = CallbackElement(name, log)
        return RetainedNode(element, CallbackNode(name, log), null)
    }

    /**
     * Minimal immutable keyed declarations exercise the real reconciler without rendering prerequisites.
     */
    private class CallbackElement(
        val name: String,
        val log: MutableList<String>,
        children: List<Element> = emptyList(),
        val failure: Throwable? = null,
        val failureOperation: String? = null,
        val onDispose: () -> Unit = {},
    ) : Element(ElementIdentity.Keyed(ElementKey(name)), Type, children) {
        companion object {
            val Type =
                ElementType(CallbackElement::class, CallbackNode::class, {}, {
                    CallbackNode(it.name, it.log, it.failure, it.failureOperation, it.onDispose)
                }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * One capable node shared by component and virtual-modifier test entries.
     */
    private class CallbackNode(
        private val name: String,
        private val log: MutableList<String>,
        private val failure: Throwable? = null,
        private val failureOperation: String? = null,
        private val onDispose: () -> Unit = {},
    ) : ModifierNode(),
        FrameCutoffNode,
        FrameTimeNode,
        LifecycleNode {
        override fun captureFrameState() = callback("capture")

        override fun commitFrameState() = callback("commit")

        override fun onFrame(time: FrameTime) {
            callback("time", " ${time.nanoseconds}")
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            onDispose()
            log.add("dispose $name")
        }

        private fun callback(
            operation: String,
            suffix: String = "",
        ) {
            log.add("$operation $name$suffix")
            if (operation == failureOperation) throw checkNotNull(failure)
        }
    }
}
