@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks allocation-driven virtualization using the same retained engine on JVM and JavaScript.
 */
internal class AdaptiveVirtualListTest {
    @Test
    fun replacedVirtualRowsKeepSemanticsThroughNestedAncestors() {
        val state = VirtualListState<Int>()
        UiTree().use { tree ->
            tree.update(
                evaluateComponentTree {
                    Stack {
                        Column {
                            VirtualList(100, { it }, { it }, state, IntSize(80, 30), 10) { item ->
                                Spacer(Modifier.size(80, 10).semantics(Semantics(label = UiText.Literal("Row $item"))))
                            }
                        }
                    }
                },
            )
            for (first in listOf(0, 97, 0)) {
                assertTrue(state.jumpToIndex(first))
                tree.measure(Constraints.fixed(80, 30))
                tree.layout()
                val visible = tree.semantics().filter { it.bounds.top in 0 until 30 }
                assertEquals((first until first + 3).map { UiText.Literal("Row $it") }, visible.map { it.semantics.label })
            }
        }
    }

    @Test
    fun fillsTheWeightedSpaceAfterFixedChildrenAndGaps() {
        val state = VirtualListState<Int>()
        val constructed = mutableListOf<Int>()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Column(Modifier.fillMaxSize(), spacing = 4) {
                    Spacer(Modifier.height(12))
                    VirtualList(
                        itemCount = 1_000_000,
                        itemAt = { it },
                        keyAt = { it },
                        state = state,
                        rowHeight = 10,
                        modifier = Modifier.weight(1f),
                    ) {
                        constructed += it
                        Spacer()
                    }
                    Spacer(Modifier.height(10))
                }
            },
        )
        assertEquals(IntSize(80, 100), tree.measure(Constraints.fixed(80, 100)))
        tree.layout()
        assertEquals(70, state.scrollState.metrics.viewportExtent)
        assertEquals((0..7).toList(), constructed)
        tree.measure(Constraints.fixed(80, 100))
        tree.layout()
        assertEquals(8, constructed.size)
        tree.close()
    }

    @Test
    fun resizeUsesTheNewVisibleWindowAndClampsTheSameScrollState() {
        val state = VirtualListState<Int>(initialIndex = 10)
        val constructed = mutableListOf<Int>()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                VirtualList(itemCount = 100, itemAt = { it }, keyAt = { it }, state = state, rowHeight = 10) {
                    constructed += it
                    Spacer()
                }
            },
        )
        tree.measure(Constraints.fixed(80, 40))
        tree.layout()
        assertEquals(100.0, state.scrollState.metrics.offset)
        assertEquals((9..14).toList(), constructed)
        assertTrue(state.jumpToIndex(99))
        tree.measure(Constraints.fixed(80, 40))
        tree.layout()
        assertEquals(960.0, state.scrollState.metrics.offset)
        tree.measure(Constraints.fixed(120, 100))
        tree.layout()
        assertEquals(100, state.scrollState.metrics.viewportExtent)
        assertEquals(900.0, state.scrollState.metrics.offset)
        assertTrue(99 in constructed)
        tree.measure(Constraints.fixed(60, 20))
        tree.layout()
        assertEquals(900.0, state.scrollState.metrics.offset)
        assertEquals(20, state.scrollState.metrics.viewportExtent)
        tree.close()
    }

    @Test
    fun zeroAllocationBuildsNoRowsAndCanGrowAgain() {
        val state = VirtualListState<Int>()
        var constructed = 0
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                VirtualList(itemCount = { 100 }, itemAt = { it }, keyAt = { it }, state = state, rowHeight = 10) {
                    constructed++
                    Spacer()
                }
            },
        )
        for (size in listOf(IntSize.Zero, IntSize(80, 0), IntSize(0, 20))) {
            assertEquals(size, tree.measure(Constraints.fixed(size.width, size.height)))
            tree.layout()
            assertEquals(0, constructed)
        }
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(3, constructed)
        tree.measure(Constraints.fixed(80, 0))
        tree.layout()
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(6, constructed)
        tree.close()
    }

    @Test
    fun nestedObservedRowsAreAttachedBeforeTheirFirstMeasurement() {
        val state = VirtualListState<Int>()
        val constructed = mutableListOf<Int>()
        val observed = mutableListOf<Int>()
        var subscriptions = 0
        var releases = 0
        val height =
            StateSource {
                subscriptions += 1
                StateSubscription(StateSnapshot(StateRevision(0), 8)) { releases += 1 }
            }
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                VirtualList(itemCount = 100, itemAt = { it }, keyAt = { it }, state = state, rowHeight = 10) { row ->
                    constructed += row
                    Observe(height) {
                        observed += row
                        Spacer(Modifier.height(it))
                    }
                }
            },
        )
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(listOf(0, 1, 2), constructed)
        assertEquals(constructed, observed)
        assertEquals(1, subscriptions)
        tree.close()
        assertEquals(1, releases)
    }

    @Test
    fun unboundedAxesAndDeclarationOnlyProjectionFailExplicitly() {
        for (constraints in listOf(Constraints(maxWidth = 80), Constraints(maxHeight = 20))) {
            val tree = UiTree()
            tree.update(
                evaluateComponentTree {
                    VirtualList(items = listOf(1), keyOf = { it }, state = VirtualListState(), rowHeight = 10) { Spacer() }
                },
            )
            val failure = assertFailsWith<IllegalArgumentException> { tree.measure(constraints) }
            assertTrue(failure.message.orEmpty().contains("bounded width and height"))
            tree.close()
        }
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                VirtualList(items = listOf(1), keyOf = { it }, state = VirtualListState(), rowHeight = 10) { Spacer() }
            },
        )
        val failure = assertFailsWith<IllegalArgumentException> { tree.projectDeclarations {} }
        assertTrue(failure.message.orEmpty().contains("remote declarations must specify viewportSize"))
        tree.close()
    }
}
