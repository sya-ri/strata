package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies linear keyed and positional retained identity matching.
 */
internal class ReconciliationTest {
    @Test
    fun keyedReorderReusesExactNodesWithoutLifecycleChurn() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("a"), id("a")), probe.element(id("b"), id("b")))))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val first = probe.created[1]
        val second = probe.created[2]
        val events = probe.events.toList()

        tree.update(probe.root(listOf(probe.element(id("b"), id("b")), probe.element(id("a"), id("a")))))

        assertEquals(3, probe.created.size)
        assertSame(first, probe.nodeForTag(id("a")))
        assertSame(second, probe.nodeForTag(id("b")))
        assertEquals(events, probe.events)
        tree.close()
    }

    @Test
    fun positionalInsertAndRemovalUseAbsoluteSiblingIndices() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(
            probe.root(
                listOf(probe.element(id("old-a")), probe.element(id("old-b")), probe.element(id("old-c"))),
            ),
        )
        val first = probe.created[1]
        val second = probe.created[2]
        val third = probe.created[3]

        tree.update(
            probe.root(
                listOf(probe.element(id("new-a")), probe.element(id("new-b"))),
            ),
        )

        assertEquals(4, probe.created.size)
        assertSame(first, probe.nodeForTag(id("new-a")))
        assertSame(second, probe.nodeForTag(id("new-b")))
        assertNotSame(third, probe.nodeForTag(id("new-b")))
        assertEquals(
            listOf(TestProbe.Event.Detach(id("old-c")), TestProbe.Event.Dispose(id("old-c"))),
            probe.events.takeLast(2),
        )
        tree.close()
    }

    @Test
    fun keyedInsertionBeforePositionalsDoesNotAliasAnAbsoluteSlot() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("old-zero")), probe.element(id("old-one")))))
        val oldZero = probe.created[1]
        val oldOne = probe.created[2]

        tree.update(
            probe.root(
                listOf(
                    probe.element(id("keyed"), id("keyed")),
                    probe.element(id("new-zero")),
                    probe.element(id("new-one")),
                ),
            ),
        )

        assertNotSame(oldZero, probe.nodeForTag(id("new-zero")))
        assertSame(oldOne, probe.nodeForTag(id("new-zero")))
        assertNotSame(oldOne, probe.nodeForTag(id("new-one")))
        tree.close()
    }

    @Test
    fun keyToPositionalReplacementCleansOldBeforeAttachingNew() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("old"), id("key")))))
        probe.events.clear()

        tree.update(probe.root(listOf(probe.element(id("new")))))

        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Attach(id("new")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun elementTypeReplacementCleansOldBeforeAttachingNew() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("old"), id("old")))
        probe.events.clear()

        tree.update(AlternateElement(probe, id("alternate")))

        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Attach(id("alternate")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun sameKeyWithDifferentElementTypeReplacesTheRetainedNode() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.element(id("old"), id("same")))
        probe.events.clear()

        tree.update(AlternateElement(probe, id("alternate"), id("same")))

        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Attach(id("alternate")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun sameParentChildTokenReplacementCleansOldBeforeAttachingNew() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("old"), id("same")))))
        probe.events.clear()

        tree.update(probe.root(listOf(AlternateElement(probe, id("alternate"), id("same")))))

        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("old")),
                TestProbe.Event.Dispose(id("old")),
                TestProbe.Event.Attach(id("alternate")),
            ),
            probe.events,
        )
        assertEquals(TreeState.Active, tree.state)
        tree.close()
    }

    @Test
    fun keyedCrossParentMoveCreatesANewNode() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(
            probe.root(
                listOf(
                    probe.element(id("parent-a"), id("parent-a"), children = listOf(probe.element(id("child"), id("child")))),
                    probe.element(id("parent-b"), id("parent-b")),
                ),
            ),
        )
        val oldChild = probe.nodeForTag(id("child"))
        probe.events.clear()

        tree.update(
            probe.root(
                listOf(
                    probe.element(id("parent-a"), id("parent-a")),
                    probe.element(id("parent-b"), id("parent-b"), children = listOf(probe.element(id("child"), id("child")))),
                ),
            ),
        )

        val newChild = probe.nodeForTag(id("child"))
        assertNotSame(oldChild, newChild)
        assertEquals(
            listOf(
                TestProbe.Event.Detach(id("child")),
                TestProbe.Event.Dispose(id("child")),
                TestProbe.Event.Attach(id("child")),
            ),
            probe.events,
        )
        tree.close()
    }

    @Test
    fun largeKeyedSiblingReverseReusesEveryNode() {
        val probe = TestProbe()
        val tree = UiTree()
        val initial = (0 until 2000).map { index -> probe.element(id("key-$index"), id("key-$index")) }
        tree.update(probe.root(initial))
        val nodes = probe.created.drop(1).associateBy { node -> node.tag }
        val reversed = initial.asReversed()

        tree.update(probe.root(reversed))

        assertEquals(2001, probe.created.size)
        val retained = probe.created.drop(1).associateBy { node -> node.tag }
        reversed.forEach { element -> assertSame(nodes[element.tag], retained[element.tag]) }
        tree.close()
    }

    @Test
    fun equalNodesUseIdentityOwnershipRatherThanEquals() {
        val shared = EqualNode()
        val firstTree = UiTree()
        firstTree.update(EqualElement(shared))
        val secondTree = UiTree()
        secondTree.update(EqualElement(EqualNode()))
        val aliasTree = UiTree()

        assertThrows(IllegalStateException::class.java) { aliasTree.update(EqualElement(shared)) }
        firstTree.update(EqualElement(shared))
        assertEquals(TreeState.Active, firstTree.state)

        firstTree.close()
        secondTree.close()
        aliasTree.close()
    }

    private fun id(value: String): TestProbe.ProbeId = TestProbe.ProbeId(value)

    /**
     * A node whose equality deliberately ignores identity.
     */
    private class EqualNode : Node() {
        override fun equals(other: Any?): Boolean = other is EqualNode

        override fun hashCode(): Int = 0
    }

    /**
     * An element returning the deliberately equal node.
     */
    private class EqualElement(
        private val node: EqualNode,
    ) : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable token for equal-node ownership tests.
         */
        companion object {
            val TYPE: ElementType<EqualElement, EqualNode> =
                ElementType(
                    elementClass = EqualElement::class,
                    nodeClass = EqualNode::class,
                    validateLocal = { },
                    createNode = { element -> element.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * An alternate stable token used to verify type replacement.
     */
    private class AlternateElement(
        private val probe: TestProbe,
        private val tag: TestProbe.ProbeId,
        key: TestProbe.ProbeId? = null,
    ) : Element(
            key?.let { value -> ElementIdentity.Keyed(ElementKey(value)) } ?: ElementIdentity.Positional,
            TYPE,
        ) {
        /**
         * Stable token for type replacement tests.
         */
        companion object {
            val TYPE: ElementType<AlternateElement, TestProbe.ProbeNode> =
                ElementType(
                    elementClass = AlternateElement::class,
                    nodeClass = TestProbe.ProbeNode::class,
                    validateLocal = { },
                    createNode = { element -> TestProbe.ProbeNode(element.probe, element.tag) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
