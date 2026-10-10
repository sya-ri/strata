@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.VirtualListElement
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Inspects exact cache storage without garbage-collection heuristics or production introspection API.
 */
internal class VirtualRowRetentionTest {
    @Test
    fun unrelatedWindowHistoryFailureDetachmentAndDisposalReleaseEveryRowRecord() {
        val state = VirtualListState<Int>()
        val models = List(2_048) { Model(it) }
        var failing = false
        val declaration =
            evaluateComponentTree {
                VirtualList(models, { it.index }, state, IntSize(8, 20), rowHeight = 10) {
                    if (failing) error("Row factory failed")
                    Spacer()
                }
            } as VirtualListElement
        val node = VirtualListElement.Node(declaration)
        val release = node.bindRuntime {}
        try {
            node.attach()
            node.prepareDeclaration()
            repeat(1_000) { step ->
                state.scrollState.scrollTo((100 + step % 1_000) * 10.0)
                assertEquals(4, node.dynamicChildren().size)
                assertEquals(4, cache(node, "cachedRows").size)
                assertEquals(4, cache(node, "cachedChildren").size)
            }
            node.sessionDetached()
            assertEquals(0, cache(node, "cachedRows").size)
            assertEquals(0, cache(node, "cachedChildren").size)
            node.sessionAttached()
            node.dynamicChildren()
            failing = true
            state.scrollState.scrollTo(15_000.0)
            assertFailsWith<IllegalStateException> { node.dynamicChildren() }
            assertEquals(0, cache(node, "cachedRows").size)
            assertEquals(0, cache(node, "cachedChildren").size)
            failing = false
            node.dynamicChildren()
            node.detach()
            assertEquals(0, cache(node, "cachedRows").size)
            node.dispose()
            assertEquals(0, cache(node, "cachedChildren").size)
        } finally {
            node.detach()
            node.dispose()
            release()
        }
    }

    private fun cache(
        node: VirtualListElement.Node,
        field: String,
    ): List<*> {
        val member = node.javaClass.getDeclaredField(field)
        member.isAccessible = true
        return member.get(node) as List<*>
    }

    /**
     * Immutable indexed model used throughout the unrelated history.
     */
    private class Model(
        val index: Int,
    )
}
