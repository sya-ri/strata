@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.layout

import dev.s7a.strata.action.ActionDispatcher
import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.action.ListLoadRequest
import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.component.VirtualListController
import dev.s7a.strata.component.VirtualListJump
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.DynamicChildrenNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal fixed-row viewport that materializes only its visible keyed item descriptions.
 *
 * Data access and deferred content construction occur on the retained tree owner thread immediately before measurement.
 * The immutable indexed source may represent a known finite range without allocating an intermediate item list.
 */
internal class VirtualListElement(
    internal val state: VirtualListState<Any>,
    internal val itemCount: Int,
    internal val itemAt: (Int) -> Any,
    internal val keyAt: (Int) -> Any,
    internal val indexOfKey: (Any) -> Int?,
    internal val itemContent: (Any) -> Element,
    internal val viewportSize: IntSize,
    internal val rowHeight: Int,
    internal val scrollRate: Int,
    internal val canLoadLeading: Boolean,
    internal val canLoadTrailing: Boolean,
    internal val actions: ActionDispatcher,
    key: ElementKey<*>?,
    modifier: Modifier,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = emptyList(),
        modifier = modifier,
    ) {
    /**
     * Retained viewport owner that reconciles visible rows and synchronizes navigation geometry.
     */
    @Suppress("TooManyFunctions") // Virtualization, input, layout, navigation, and lifecycle meet at one retained viewport owner.
    internal class Node(
        initial: VirtualListElement,
    ) : RetainedNode(),
        DynamicChildrenNode,
        MeasureNode,
        LayoutNode,
        PointerInputNode,
        ClipChildrenNode,
        LifecycleNode,
        VirtualListController<Any> {
        private var state = initial.state
        private var itemCount = initial.itemCount
        private var itemAt = initial.itemAt
        private var keyAt = initial.keyAt
        private var indexOfKey = initial.indexOfKey
        private var itemContent = initial.itemContent
        private var viewportSize = initial.viewportSize
        private var rowHeight = initial.rowHeight
        private var scrollRate = initial.scrollRate
        private var canLoadLeading = initial.canLoadLeading
        private var canLoadTrailing = initial.canLoadTrailing
        private var actions = initial.actions
        private var visibleStart = 0
        private var cachedStart = 0
        private var cachedEndExclusive = 0
        private var cachedChildren: List<Element> = emptyList()
        private var observer: ScrollStateObserver? = null
        private var attached = false

        override fun dynamicChildren(): List<Element> {
            if (itemCount == 0) {
                visibleStart = 0
                cachedStart = 0
                cachedEndExclusive = 0
                cachedChildren = emptyList()
                return emptyList()
            }
            val offset = state.scrollState.metrics.offset
            val first = (offset / rowHeight.toDouble()).toInt().coerceIn(0, itemCount - 1)
            val lastVisible = ((offset + viewportSize.height - 1.0) / rowHeight.toDouble()).toInt().coerceIn(first, itemCount - 1)
            visibleStart = maxOf(0, first - OVERSCAN_ROWS)
            val endExclusive = minOf(itemCount, lastVisible + OVERSCAN_ROWS + 1)
            if (cachedChildren.isNotEmpty() && cachedStart == visibleStart && cachedEndExclusive == endExclusive) {
                return cachedChildren
            }
            cachedStart = visibleStart
            cachedEndExclusive = endExclusive
            cachedChildren =
                (visibleStart until endExclusive).map { index ->
                    val item = itemAt(index)
                    StackElement(
                        contentAlignment = Alignment.TopStart,
                        key = ElementKey(keyAt(index)),
                        children = listOf(itemContent(item)),
                        modifier = Modifier.Empty,
                    )
                }
            return cachedChildren
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(viewportSize)) { "VirtualList constraints must contain its requested viewport size." }
            for (index in 0 until scope.childCount) {
                scope.measureChild(index, Constraints.fixed(viewportSize.width, rowHeight))
            }
            state.scrollState.updateGeometry(
                viewportExtent = viewportSize.height,
                contentExtent = Math.multiplyExact(itemCount, rowHeight),
                origin = checkNotNull(observer),
            )
            return viewportSize
        }

        override fun layout(scope: LayoutScope) {
            val offset =
                state.scrollState.metrics.offset
                    .toInt()
            for (childIndex in 0 until scope.childCount) {
                val itemIndex = Math.addExact(visibleStart, childIndex)
                val top = Math.subtractExact(Math.multiplyExact(itemIndex, rowHeight), offset)
                scope.placeChild(childIndex, IntOffset(0, top))
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            if (event is PointerEvent.Scroll) {
                val delta = event.deltaY * scrollRate.toDouble()
                if (delta.isFinite()) state.scrollState.scrollBy(delta, checkNotNull(observer))
                requestBoundaryItems(delta)
                invalidate(DirtyMask.of(DirtyPhase.Measure))
                InputResult.Consumed
            } else {
                InputResult.Ignored
            }

        override fun jump(target: VirtualListJump<Any>): Boolean {
            val index =
                when (target) {
                    is VirtualListJump.Index -> target.value.takeIf { value -> value < itemCount }
                    is VirtualListJump.Key -> indexOfKey(target.value)
                } ?: return false
            state.scrollState.scrollTo(Math.multiplyExact(index, rowHeight).toDouble())
            return true
        }

        override fun refresh() {
            cachedChildren = emptyList()
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun attach() {
            observer = state.scrollState.observe { invalidate(DirtyMask.of(DirtyPhase.Measure)) }
            state.attach(this)
            attached = true
        }

        override fun detach() {
            if (attached) state.detach(this)
            attached = false
        }

        override fun dispose() {
            if (attached) state.detach(this)
            attached = false
            observer?.close()
            observer = null
            cachedChildren = emptyList()
        }

        /**
         * Replaces the immutable source and viewport policy while retaining navigation state.
         */
        internal fun update(current: VirtualListElement): DirtyMask {
            val stateChanged = state !== current.state
            val previousOffset = state.scrollState.metrics.offset
            val previousFirst =
                if (itemCount == 0) {
                    null
                } else {
                    (previousOffset / rowHeight.toDouble()).toInt().coerceIn(0, itemCount - 1)
                }
            val anchorKey = previousFirst?.let(keyAt)
            val intraRowOffset = previousFirst?.let { index -> previousOffset - Math.multiplyExact(index, rowHeight).toDouble() }
            if (stateChanged && attached) {
                state.detach(this)
                observer?.close()
            }
            state = current.state
            itemCount = current.itemCount
            itemAt = current.itemAt
            keyAt = current.keyAt
            indexOfKey = current.indexOfKey
            itemContent = current.itemContent
            viewportSize = current.viewportSize
            rowHeight = current.rowHeight
            scrollRate = current.scrollRate
            canLoadLeading = current.canLoadLeading
            canLoadTrailing = current.canLoadTrailing
            actions = current.actions
            cachedChildren = emptyList()
            cachedStart = 0
            cachedEndExclusive = 0
            if (stateChanged && attached) {
                observer = state.scrollState.observe { invalidate(DirtyMask.of(DirtyPhase.Measure)) }
                state.attach(this)
            } else if (attached && anchorKey != null && intraRowOffset != null) {
                val nextAnchor = current.indexOfKey(anchorKey)
                if (nextAnchor != null) {
                    state.scrollState.updateGeometry(
                        viewportExtent = viewportSize.height,
                        contentExtent = Math.multiplyExact(itemCount, rowHeight),
                        origin = checkNotNull(observer),
                    )
                    state.scrollState.scrollTo(
                        Math.multiplyExact(nextAnchor, rowHeight).toDouble() + intraRowOffset,
                        checkNotNull(observer),
                    )
                }
            }
            return DirtyMask.of(DirtyPhase.Measure)
        }

        private fun requestBoundaryItems(delta: Double) {
            val visibleRows = Math.addExact((viewportSize.height - 1) / rowHeight, 1)
            val suggested = maxOf(visibleRows, LOAD_REQUEST_MINIMUM)
            val firstVisible = (state.scrollState.metrics.offset / rowHeight.toDouble()).toInt()
            if (delta < 0.0 && canLoadLeading && firstVisible <= LOAD_THRESHOLD_ROWS) {
                actions.dispatch(ComponentActions.LeadingItemsRequested, ListLoadRequest(suggested))
            }
            val lastVisible = minOf(itemCount - 1, firstVisible + visibleRows - 1)
            if (0.0 < delta && canLoadTrailing && itemCount - 1 - lastVisible <= LOAD_THRESHOLD_ROWS) {
                actions.dispatch(ComponentActions.TrailingItemsRequested, ListLoadRequest(suggested))
            }
        }
    }

    /**
     * Owns the stable element type and local source validation.
     */
    companion object {
        private const val OVERSCAN_ROWS = 1
        private const val LOAD_THRESHOLD_ROWS = 2
        private const val LOAD_REQUEST_MINIMUM = 8
        private val TYPE: ElementType<VirtualListElement, Node> =
            ElementType(
                elementClass = VirtualListElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(0 <= element.itemCount) { "VirtualList item count must be non-negative." }
                    require(0 < element.viewportSize.width && 0 < element.viewportSize.height) { "VirtualList viewport size must be positive." }
                    require(0 < element.rowHeight) { "VirtualList row height must be positive." }
                    require(0 < element.scrollRate) { "VirtualList scroll rate must be positive." }
                    require(element.children.isEmpty()) { "VirtualList declarative children must be dynamically materialized." }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
