@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onLeadingItemsRequested
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onTrailingItemsRequested
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies visible-only materialization, cached clean ranges, and absolute navigation.
 */
internal class VirtualListTest {
    @Test
    fun sessionInputBurstsMaterializeNewRowsBeforeHitTestingWithoutEvaluatingContent() {
        val state = VirtualListState<Int>()
        val constructed = ArrayList<Int>()
        val hits = ArrayList<Pair<Int, IntOffset>>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls++
                evaluateComponentTree {
                    VirtualList(
                        itemCount = 1_000,
                        itemAt = { it },
                        keyAt = { it },
                        state = state,
                        viewportSize = IntSize(80, 30),
                        rowHeight = 10,
                    ) { item ->
                        constructed.add(item)
                        Spacer(
                            modifier =
                                Modifier.Empty.size(80, 10).onPointerEvent { event, position ->
                                    if (event is PointerEvent.Move) {
                                        hits.add(item to position)
                                        InputResult.Consumed
                                    } else {
                                        InputResult.Ignored
                                    }
                                },
                        )
                    }
                }
            }
        session.attach()
        session.frame(Constraints.fixed(80, 30))
        session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        assertEquals(0 to IntOffset(1, 1), hits.last())
        val before = constructed.size
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), 0.0, 10.0)))
        assertEquals(before, constructed.size)
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        val offset =
            state.scrollState.metrics.offset
                .toInt()
        assertTrue(0 < offset)
        assertEquals(offset / 10 to IntOffset(1, offset % 10 + 1), hits.last())
        assertTrue(before < constructed.size)
        assertEquals(1, contentCalls)
        val committed = session.frame(Constraints.fixed(80, 30))
        assertEquals(committed, session.frame(Constraints.fixed(80, 30)))
        session.close()
    }

    @Test
    fun materializesOnlyViewportRowsAndReusesAnUnchangedRange() {
        val state = VirtualListState<Int>()
        val constructed = ArrayList<Int>()
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = 1_000_000,
                    itemAt = { index -> index },
                    keyAt = { index -> index },
                    state = state,
                    viewportSize = IntSize(100, 30),
                    rowHeight = 10,
                ) { item ->
                    constructed += item
                    Spacer()
                }
            }
        val tree = UiTree()
        tree.update(root)
        assertEquals(IntSize(100, 30), tree.measure(Constraints.fixed(100, 30)))
        tree.layout()
        assertEquals(listOf(0, 1, 2, 3), constructed)

        tree.measure(Constraints.fixed(100, 30))
        tree.layout()
        assertEquals(listOf(0, 1, 2, 3), constructed)

        assertTrue(state.jumpToIndex(500_000))
        tree.measure(Constraints.fixed(100, 30))
        tree.layout()
        assertEquals(listOf(499_999, 500_000, 500_001, 500_002, 500_003), constructed.takeLast(5))
        assertEquals(5_000_000.0, state.scrollState.metrics.offset)
        tree.close()
    }

    @Test
    fun appliesKeyJumpRequestedBeforeAttachment() {
        val state = VirtualListState<Int>()
        assertTrue(state.jumpToKey(900))
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = 1_000,
                    itemAt = { index -> index },
                    keyAt = { index -> index },
                    state = state,
                    viewportSize = IntSize(80, 20),
                    rowHeight = 10,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(9_000.0, state.scrollState.metrics.offset)
        tree.close()
    }

    @Test
    fun preservesTheVisibleKeyWhenRowsArePrepended() {
        val state = VirtualListState<Int>(initialIndex = 10)

        fun root(items: List<Int>) =
            evaluateComponentTree {
                VirtualList(
                    items = items,
                    keyOf = { item -> item },
                    state = state,
                    viewportSize = IntSize(80, 20),
                    rowHeight = 10,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root((100 until 200).toList()))
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(100.0, state.scrollState.metrics.offset)

        tree.update(root((0 until 200).toList()))
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(1_100.0, state.scrollState.metrics.offset)
        tree.close()
    }

    @Test
    fun requestsBothLoadedBoundariesFromDirectionalInput() {
        val state = VirtualListState<Int>()
        var leading = 0
        var trailing = 0
        val modifier =
            Modifier.Empty
                .onLeadingItemsRequested { leading += it.suggestedCount }
                .onTrailingItemsRequested { trailing += it.suggestedCount }
        val root =
            evaluateComponentTree {
                VirtualList(
                    items = (0 until 10).toList(),
                    keyOf = { item -> item },
                    state = state,
                    viewportSize = IntSize(80, 30),
                    rowHeight = 10,
                    canLoadLeading = true,
                    canLoadTrailing = true,
                    modifier = modifier,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        tree.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), deltaX = 0.0, deltaY = -1.0))
        assertEquals(8, leading)

        state.scrollState.scrollTo(state.scrollState.metrics.maximumOffset)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        tree.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), deltaX = 0.0, deltaY = 1.0))
        assertEquals(8, trailing)
        tree.close()
    }

    @Test
    fun refreshSamplesDynamicCountOnceAndRebuildsSameCountRows() {
        val state = VirtualListState<Int>()
        val items = mutableListOf(0 to "first", 1 to "second", 2 to "third")
        val constructed = ArrayList<String>()
        var countSamples = 0
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = {
                        countSamples += 1
                        items.size
                    },
                    itemAt = items::get,
                    keyAt = { index -> items[index].first },
                    state = state,
                    viewportSize = IntSize(80, 20),
                    rowHeight = 10,
                ) { item ->
                    constructed += item.second
                    Spacer()
                }
            }
        assertEquals(1, countSamples)
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        assertEquals(1, countSamples)
        assertEquals(listOf("first", "second", "third"), constructed)

        items[0] = 0 to "replaced"
        state.refresh()
        assertEquals(2, countSamples)
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()

        assertEquals(2, countSamples)
        assertEquals(listOf("replaced", "second", "third"), constructed.takeLast(3))
        tree.close()
    }

    @Test
    fun leadingLoadRefreshPreservesStableAnchorAndIntraRowOffset() {
        val state = VirtualListState<Int>()
        val items = (0 until 10).toMutableList()
        var suggestedCount = 0
        val modifier =
            Modifier.Empty.onLeadingItemsRequested { request ->
                suggestedCount = request.suggestedCount
                items.addAll(0, (-request.suggestedCount until 0).toList())
                state.refresh()
            }
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = { items.size },
                    itemAt = items::get,
                    keyAt = items::get,
                    state = state,
                    viewportSize = IntSize(80, 30),
                    rowHeight = 10,
                    canLoadLeading = true,
                    modifier = modifier,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        state.scrollState.scrollTo(15.0)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()

        tree.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), deltaX = 0.0, deltaY = -1.0))

        assertEquals(8, suggestedCount)
        assertEquals((-8 until 10).toList(), items)
        assertEquals(85.0, state.scrollState.metrics.offset)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        tree.close()
    }

    @Test
    fun trailingLoadRefreshMakesAppendedWindowAvailableToJumps() {
        val state = VirtualListState<Int>()
        val items = (0 until 10).toMutableList()
        var suggestedCount = 0
        val modifier =
            Modifier.Empty.onTrailingItemsRequested { request ->
                suggestedCount = request.suggestedCount
                val start = items.size
                items.addAll(start until start + request.suggestedCount)
                state.refresh()
            }
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = { items.size },
                    itemAt = items::get,
                    keyAt = items::get,
                    state = state,
                    viewportSize = IntSize(80, 30),
                    rowHeight = 10,
                    canLoadTrailing = true,
                    modifier = modifier,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        state.scrollState.scrollTo(state.scrollState.metrics.maximumOffset)
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()

        tree.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), deltaX = 0.0, deltaY = 1.0))

        assertEquals(8, suggestedCount)
        assertEquals((0 until 18).toList(), items)
        assertTrue(state.jumpToKey(17))
        assertTrue(state.jumpToIndex(17))
        assertFalse(state.jumpToIndex(18))
        tree.measure(Constraints.fixed(80, 30))
        tree.layout()
        tree.close()
    }

    @Test
    fun invalidRefreshCountLeavesLastValidGeometryAndRange() {
        val state = VirtualListState<Int>()
        val items = (0 until 3).toList()
        var reportedCount = items.size
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = { reportedCount },
                    itemAt = items::get,
                    keyAt = items::get,
                    state = state,
                    viewportSize = IntSize(80, 20),
                    rowHeight = 10,
                ) { Spacer() }
            }
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()
        val validMetrics = state.scrollState.metrics

        reportedCount = -1
        assertThrows(IllegalArgumentException::class.java) { state.refresh() }

        assertEquals(validMetrics, state.scrollState.metrics)
        assertTrue(state.jumpToIndex(2))
        assertFalse(state.jumpToIndex(3))
        tree.close()
    }

    @Test
    fun refreshRequestedBeforeAttachmentSamplesTheLatestSource() {
        val state = VirtualListState<Int>()
        val items = mutableListOf(0, 1)
        var countSamples = 0
        val root =
            evaluateComponentTree {
                VirtualList(
                    itemCount = {
                        countSamples += 1
                        items.size
                    },
                    itemAt = items::get,
                    keyAt = items::get,
                    state = state,
                    viewportSize = IntSize(80, 20),
                    rowHeight = 10,
                ) { Spacer() }
            }
        items += 2
        state.refresh()
        val tree = UiTree()

        tree.update(root)
        tree.measure(Constraints.fixed(80, 20))
        tree.layout()

        assertEquals(2, countSamples)
        assertTrue(state.jumpToIndex(2))
        tree.close()
    }
}
