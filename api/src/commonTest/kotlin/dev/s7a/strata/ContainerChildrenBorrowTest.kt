package dev.s7a.strata

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies direct internal child-membership borrowing and terminal release.
 */
internal class ContainerChildrenBorrowTest {
    @Test
    fun borrowedMembershipIsReleasedAndConstructedChildrenRemainDetached() {
        val scope = UiScope.createRoot()
        scope.element(ExternalElement())
        scope.element(ExternalElement())
        val borrowed = scope.borrowChildElements()
        assertEquals(2, borrowed.size)
        assertFailsWith<IllegalStateException> { scope.element(ExternalElement()) }
        val detached =
            try {
                ExternalElement(borrowed)
            } finally {
                scope.close()
            }
        assertTrue(borrowed.isEmpty())
        assertEquals(2, detached.children.size)
    }

    /**
     * Downstream-style declaration using only the public defensive constructor.
     */
    private class ExternalElement(
        children: List<Element> = emptyList(),
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<ExternalElement, Node> =
                ElementType(
                    elementClass = ExternalElement::class,
                    nodeClass = Node::class,
                    validateLocal = {},
                    createNode = { object : Node() {} },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
