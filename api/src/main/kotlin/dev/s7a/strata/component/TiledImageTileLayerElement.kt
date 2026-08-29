@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameCutoffNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import java.util.LinkedHashMap
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained tile paint layer installed as the first child of every [TiledImageElement].
 *
 * This node owns the attachment's current visible tile observations and immutable row-major paint plan.
 * Its placement and local command cache are independent from application overlay reconciliation.
 *
 * @param source externally owned immutable source generation.
 * @param bounds source bounds snapshotted by the public component declaration.
 * @param levels immutable finest-to-coarsest level snapshot.
 * @param state caller-owned viewport transform.
 * @param destinationSize exact logical viewport size.
 * @param cachePolicy bounded current working-set policy.
 */
internal class TiledImageTileLayerElement(
    private val source: TiledImageSource,
    private val bounds: LongRect,
    private val levels: List<TiledImageLevel>,
    private val state: PanZoomState,
    private val destinationSize: IntSize,
    private val cachePolicy: TiledImageCachePolicy,
) : Element(
        identity = ElementIdentity.Positional,
        type = TYPE,
        modifier = Modifier.Empty,
    ) {
    /**
     * Retained source binding, frame cutoff, view planning, and sampled-image command producer.
     *
     * @param source initial externally owned source generation.
     * @param bounds initial content bounds.
     * @param levels initial immutable resolution levels.
     * @param state initial caller-owned transform.
     * @param destinationSize initial viewport size.
     * @param cachePolicy initial bounded working-set policy.
     */
    @Suppress("TooManyFunctions")
    private class Node(
        private var source: TiledImageSource?,
        private var bounds: LongRect,
        private var levels: List<TiledImageLevel>,
        private var state: PanZoomState?,
        private var destinationSize: IntSize,
        private var cachePolicy: TiledImageCachePolicy,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        FrameCutoffNode,
        SessionAttachmentNode {
        private val frameGate: Any = Any()
        private val entries: MutableMap<TiledImageTileId, TileEntry> = LinkedHashMap()
        private var plan: TilePlan = TilePlan.Empty
        private var observer: PanZoomStateObserver? = null
        private var active: Boolean = false

        override fun attach() {
            sessionAttached()
        }

        override fun sessionAttached() {
            if (active) return
            active = true
            observer = observeState(checkNotNull(state))
            invalidate(DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint))
        }

        override fun sessionDetached() {
            active = false
            plan = TilePlan.Empty
            val previousObserver = observer
            observer = null
            val removed = removeAllEntries()
            closeResources(removed, previousObserver)
        }

        override fun detach() {
            sessionDetached()
        }

        override fun dispose() {
            source = null
            state = null
            sessionDetached()
        }

        override fun captureFrameState() {
            synchronized(frameGate) {
                entries.values.forEach(TileEntry::captureFrameLocked)
            }
        }

        override fun commitFrameState() {
            entries.values.forEach(TileEntry::validateCaptured)
            var changed = false
            synchronized(frameGate) {
                entries.values.forEach { entry ->
                    if (entry.applyCapturedLocked()) changed = true
                }
            }
            if (changed) invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "The tiled image paint layer cannot have children." }
            require(constraints.isSatisfiedBy(destinationSize)) { "Tiled image layer constraints must contain its requested size." }
            return destinationSize
        }

        override fun layout(scope: LayoutScope) {
            check(scope.childCount == 0) { "The tiled image paint layer cannot place children." }
            if (active.not()) return
            val next = createPlan(checkNotNull(state).metrics)
            reconcileEntries(next.requiredIds)
            plan = next
        }

        override fun paint(scope: PaintScope) {
            plan.cells.forEach { cell ->
                val ready = entries[cell.id]?.committedTile() as? TiledImageTile.Ready ?: return@forEach
                scope.sampledImage(
                    image = ready.image,
                    source =
                        FloatRect(
                            0.0f,
                            0.0f,
                            ready.image.size.width
                                .toFloat(),
                            ready.image.size.height
                                .toFloat(),
                        ),
                    localDestination = destination(cell, plan.center, plan.scale),
                    alphaCutoff = 0.0f,
                )
            }
        }

        /**
         * Reconciles source identity, transform, size, and working-set bounds.
         *
         * Source replacement clears old observations before any replacement tile is requested.
         * State replacement preserves source identity but rebuilds view placement and observation on the active owner thread.
         *
         * @param current incoming immutable layer description.
         * @return minimal retained invalidation for the changed contract.
         */
        internal fun update(current: TiledImageTileLayerElement): DirtyMask {
            val sourceChanged = source !== current.source
            val geometryChanged = bounds != current.bounds || levels != current.levels
            check(sourceChanged || geometryChanged.not()) {
                "Tiled image geometry cannot change without replacing its source identity."
            }
            val stateChanged = state !== current.state
            val sizeChanged = destinationSize != current.destinationSize
            val policyChanged = cachePolicy != current.cachePolicy
            bounds = current.bounds
            levels = current.levels
            destinationSize = current.destinationSize
            cachePolicy = current.cachePolicy
            if (sourceChanged) {
                source = current.source
                plan = TilePlan.Empty
                closeEntries(removeAllEntries())
            }
            if (stateChanged) {
                val previous = observer
                observer = null
                state = current.state
                previous?.close()
                if (active) observer = observeState(current.state)
            }
            return when {
                sizeChanged -> DirtyMask.of(DirtyPhase.Measure)
                sourceChanged || geometryChanged || stateChanged || policyChanged -> DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint)
                else -> DirtyMask.None
            }
        }

        private fun observeState(observed: PanZoomState): PanZoomStateObserver =
            observed.observe {
                invalidate(DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint))
            }

        private fun createPlan(metrics: PanZoomMetrics): TilePlan {
            check(metrics.geometryKnown) { "Tiled image transform geometry must be known before layout." }
            check(metrics.viewportSize == destinationSize && metrics.contentBounds == bounds) {
                "Tiled image transform geometry does not match its viewport."
            }
            val visible = visibleContent(metrics)
            val preferred = preferredLevel(metrics.scale)
            for (selectedLevel in preferred until levels.size) {
                val candidate = candidatePlan(selectedLevel, visible, metrics)
                if (candidate != null) return candidate
            }
            error("The coarsest visible tiled image working set exceeds its cache policy.")
        }

        private fun preferredLevel(scale: Double): Int {
            var selected = 0
            levels.forEachIndexed { index, level ->
                val sourcePixelExtent = scale * level.contentUnitsPerPixel.toDouble()
                if (sourcePixelExtent <= 1.0) selected = index
            }
            return selected
        }

        private fun candidatePlan(
            selectedLevel: Int,
            visible: VisibleContent,
            metrics: PanZoomMetrics,
        ): TilePlan? {
            val requiredRanges =
                (selectedLevel until levels.size).map { level ->
                    val overscan = if (level == selectedLevel) cachePolicy.overscanTiles else 0
                    LevelRange(level, tileRange(level, visible, overscan))
                }
            if (fitsPolicy(requiredRanges).not()) return null
            val required = ArrayList<TiledImageTileId>()
            requiredRanges.forEach { levelRange ->
                levelRange.range.forEach { column, row ->
                    required.add(TiledImageTileId(levelRange.level, column, row))
                }
            }
            val cells = ArrayList<TileCell>()
            for (levelIndex in levels.lastIndex downTo selectedLevel) {
                tileRange(levelIndex, visible, 0).forEach { column, row ->
                    cells.add(cell(levelIndex, column, row))
                }
            }
            return TilePlan(cells, required, metrics.center, metrics.scale)
        }

        private fun fitsPolicy(ranges: List<LevelRange>): Boolean {
            var entries = 0L
            var bytes = 0L
            return try {
                ranges.forEach { levelRange ->
                    val count = levelRange.range.count()
                    entries = Math.addExact(entries, count)
                    val level = levels[levelRange.level]
                    val pixels = Math.multiplyExact(level.tilePixelSize.width.toLong(), level.tilePixelSize.height.toLong())
                    val tileBytes = Math.multiplyExact(pixels, 4L)
                    bytes = Math.addExact(bytes, Math.multiplyExact(count, tileBytes))
                }
                entries <= cachePolicy.maxEntries.toLong() && bytes <= cachePolicy.maxBytes
            } catch (_: ArithmeticException) {
                false
            }
        }

        private fun visibleContent(metrics: PanZoomMetrics): VisibleContent {
            val halfWidth = (destinationSize.width.toDouble() / metrics.scale / 2.0).coerceAtLeast(Double.MIN_VALUE)
            val halfHeight = (destinationSize.height.toDouble() / metrics.scale / 2.0).coerceAtLeast(Double.MIN_VALUE)
            return VisibleContent(
                center = metrics.center,
                halfWidth = halfWidth,
                halfHeight = halfHeight,
            )
        }

        private fun tileRange(
            levelIndex: Int,
            visible: VisibleContent,
            overscan: Int,
        ): TileRange {
            val level = levels[levelIndex]
            val width = tileContentWidth(level)
            val height = tileContentHeight(level)
            val columns = tileAxisRange(bounds.left, bounds.right, width, visible.center.x, visible.halfWidth, overscan)
            val rows = tileAxisRange(bounds.top, bounds.bottom, height, visible.center.y, visible.halfHeight, overscan)
            return TileRange(
                firstColumn = columns.first,
                lastColumnExclusive = columns.lastExclusive,
                firstRow = rows.first,
                lastRowExclusive = rows.lastExclusive,
            )
        }

        private fun tileAxisRange(
            minimumContent: Long,
            maximumContent: Long,
            tileExtent: Long,
            center: Double,
            halfExtent: Double,
            overscan: Int,
        ): TileAxisRange {
            val minimumIndex = Math.floorDiv(minimumContent, tileExtent)
            val maximumIndex = Math.addExact(Math.floorDiv(Math.subtractExact(maximumContent, 1L), tileExtent), 1L)
            val visibleFirst =
                lowerBoundTile(minimumIndex, maximumIndex) { index ->
                    val left = Math.multiplyExact(index, tileExtent)
                    val right = Math.addExact(left, tileExtent)
                    -halfExtent < relativeCoordinate(right, center)
                }
            val visibleLastExclusive =
                lowerBoundTile(visibleFirst, maximumIndex) { index ->
                    val left = Math.multiplyExact(index, tileExtent)
                    halfExtent <= relativeCoordinate(left, center)
                }
            val margin = overscan.toLong()
            return TileAxisRange(
                first = maxOf(minimumIndex, saturatingSubtract(visibleFirst, margin)),
                lastExclusive = minOf(maximumIndex, saturatingAdd(visibleLastExclusive, margin)),
            )
        }

        private fun lowerBoundTile(
            first: Long,
            lastExclusive: Long,
            matches: (Long) -> Boolean,
        ): Long {
            var low = first
            var high = lastExclusive
            while (low < high) {
                val distance = Math.subtractExact(high, low)
                val middle = Math.addExact(low, distance / 2L)
                if (matches(middle)) {
                    high = middle
                } else {
                    low = Math.incrementExact(middle)
                }
            }
            return low
        }

        private fun cell(
            levelIndex: Int,
            column: Long,
            row: Long,
        ): TileCell {
            val level = levels[levelIndex]
            val width = tileContentWidth(level)
            val height = tileContentHeight(level)
            val left = Math.multiplyExact(column, width)
            val top = Math.multiplyExact(row, height)
            return TileCell(
                id = TiledImageTileId(levelIndex, column, row),
                left = left,
                top = top,
                right = Math.addExact(left, width),
                bottom = Math.addExact(top, height),
            )
        }

        private fun reconcileEntries(requiredIds: List<TiledImageTileId>) {
            val required = requiredIds.toHashSet()
            val removed = ArrayList<TileEntry>()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in required) continue
                iterator.remove()
                removed.add(entry.value)
            }
            closeEntries(removed)
            requiredIds.forEach { id ->
                if (entries.containsKey(id).not()) entries[id] = openEntry(id)
            }
        }

        private fun openEntry(id: TiledImageTileId): TileEntry {
            val expectedSize = levels[id.level].tilePixelSize
            val entry = TileEntry(frameGate, expectedSize)
            return runCatching {
                val stateSource = checkNotNull(source).tile(id)
                entry.install(stateSource.subscribe(entry::enqueue))
                entry
            }.getOrElse { failure ->
                val cleanupFailure = runCatching(entry::close).exceptionOrNull()
                if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                throw failure
            }
        }

        private fun destination(
            cell: TileCell,
            center: DoubleOffset,
            scale: Double,
        ): FloatRect {
            val centerX = destinationSize.width / 2.0
            val centerY = destinationSize.height / 2.0
            return FloatRect(
                (relativeCoordinate(cell.left, center.x) * scale + centerX).toFiniteFloat(),
                (relativeCoordinate(cell.top, center.y) * scale + centerY).toFiniteFloat(),
                (relativeCoordinate(cell.right, center.x) * scale + centerX).toFiniteFloat(),
                (relativeCoordinate(cell.bottom, center.y) * scale + centerY).toFiniteFloat(),
            )
        }

        private fun relativeCoordinate(
            value: Long,
            center: Double,
        ): Double {
            val integerCenter = center.toLong()
            val integerDelta =
                try {
                    Math.subtractExact(value, integerCenter)
                } catch (_: ArithmeticException) {
                    return value.toDouble() - center
                }
            return integerDelta.toDouble() - (center - integerCenter.toDouble())
        }

        private fun removeAllEntries(): List<TileEntry> {
            val removed = entries.values.toList()
            entries.clear()
            return removed
        }

        private fun closeResources(
            removed: List<TileEntry>,
            previousObserver: PanZoomStateObserver?,
        ) {
            var failure: Throwable? = null
            removed.asReversed().forEach { entry -> failure = captureFailure(failure, entry::close) }
            failure = captureFailure(failure) { previousObserver?.close() }
            failure?.let { thrown -> throw thrown }
        }

        private fun closeEntries(removed: List<TileEntry>) {
            closeResources(removed, null)
        }

        private fun captureFailure(
            previous: Throwable?,
            action: () -> Unit,
        ): Throwable? {
            val next = runCatching(action).exceptionOrNull() ?: return previous
            if (previous == null) return next
            if (previous !== next) previous.addSuppressed(next)
            return previous
        }

        private fun tileContentWidth(level: TiledImageLevel): Long = Math.multiplyExact(level.tilePixelSize.width.toLong(), level.contentUnitsPerPixel)

        private fun tileContentHeight(level: TiledImageLevel): Long = Math.multiplyExact(level.tilePixelSize.height.toLong(), level.contentUnitsPerPixel)

        private fun saturatingSubtract(
            value: Long,
            amount: Long,
        ): Long = if (value < Long.MIN_VALUE + amount) Long.MIN_VALUE else value - amount

        private fun saturatingAdd(
            value: Long,
            amount: Long,
        ): Long = if (Long.MAX_VALUE - amount < value) Long.MAX_VALUE else value + amount

        private fun Double.toFiniteFloat(): Float {
            val converted = toFloat()
            require(converted.isFinite()) { "Tiled image destination geometry must be representable as floats." }
            return converted
        }

        private class TileEntry(
            private val frameGate: Any,
            private val expectedSize: IntSize,
        ) : AutoCloseable {
            private var committed: StateSnapshot<TiledImageTile>? = null
            private var pending: StateSnapshot<TiledImageTile>? = null
            private var captured: StateSnapshot<TiledImageTile>? = null
            private var closeAction: (() -> Unit)? = null
            private var frameCaptured: Boolean = false
            private var closed: Boolean = false

            fun install(subscription: StateSubscription<TiledImageTile>) {
                val initial = subscription.initialSnapshot
                val action = subscription.retainCloseAction()
                synchronized(frameGate) {
                    check(closed.not()) { "A closed tiled image observation cannot install a subscription." }
                    closeAction = action
                }
                requireTile(initial.value)
                synchronized(frameGate) {
                    committed = initial
                    if (pending?.revision?.let { revision -> revision <= initial.revision } == true) pending = null
                }
            }

            fun enqueue(snapshot: StateSnapshot<TiledImageTile>) {
                synchronized(frameGate) {
                    if (closed) return
                    val committedRevision = committed?.revision
                    val capturedRevision = captured?.revision
                    val pendingRevision = pending?.revision
                    if (committedRevision != null && snapshot.revision <= committedRevision) return
                    if (capturedRevision != null && snapshot.revision <= capturedRevision) return
                    if (pendingRevision != null && snapshot.revision <= pendingRevision) return
                    pending = snapshot
                }
            }

            fun captureFrameLocked() {
                check(Thread.holdsLock(frameGate)) { "A tiled image frame cutoff requires its shared gate." }
                check(closed.not()) { "A closed tiled image observation cannot capture a frame." }
                check(frameCaptured.not()) { "A tiled image frame cutoff is already captured." }
                captured = pending
                pending = null
                frameCaptured = true
            }

            fun validateCaptured() {
                val next =
                    synchronized(frameGate) {
                        check(frameCaptured) { "A tiled image frame must be captured before commit." }
                        captured
                    }
                if (next != null) requireTile(next.value)
            }

            fun applyCapturedLocked(): Boolean {
                check(Thread.holdsLock(frameGate)) { "A tiled image frame commit requires its shared gate." }
                check(frameCaptured) { "A tiled image frame must be captured before commit." }
                frameCaptured = false
                val next = captured
                captured = null
                if (closed || next == null) return false
                committed = next
                return true
            }

            fun committedTile(): TiledImageTile? = synchronized(frameGate) { committed?.value }

            override fun close() {
                val action =
                    synchronized(frameGate) {
                        if (closed) return
                        closed = true
                        committed = null
                        pending = null
                        captured = null
                        frameCaptured = false
                        val action = closeAction
                        closeAction = null
                        action
                    }
                action?.invoke()
            }

            private fun requireTile(tile: TiledImageTile) {
                if (tile is TiledImageTile.Ready) {
                    require(tile.image.size == expectedSize) { "Ready tiled image dimensions must match their level." }
                }
            }
        }

        private data class VisibleContent(
            val center: DoubleOffset,
            val halfWidth: Double,
            val halfHeight: Double,
        )

        private data class TileAxisRange(
            val first: Long,
            val lastExclusive: Long,
        )

        private data class TileRange(
            val firstColumn: Long,
            val lastColumnExclusive: Long,
            val firstRow: Long,
            val lastRowExclusive: Long,
        ) {
            fun count(): Long =
                Math.multiplyExact(
                    Math.subtractExact(lastColumnExclusive, firstColumn),
                    Math.subtractExact(lastRowExclusive, firstRow),
                )

            fun forEach(action: (Long, Long) -> Unit) {
                var row = firstRow
                while (row < lastRowExclusive) {
                    var column = firstColumn
                    while (column < lastColumnExclusive) {
                        action(column, row)
                        column = Math.incrementExact(column)
                    }
                    row = Math.incrementExact(row)
                }
            }
        }

        private data class LevelRange(
            val level: Int,
            val range: TileRange,
        )

        private data class TileCell(
            val id: TiledImageTileId,
            val left: Long,
            val top: Long,
            val right: Long,
            val bottom: Long,
        )

        private data class TilePlan(
            val cells: List<TileCell>,
            val requiredIds: List<TiledImageTileId>,
            val center: DoubleOffset,
            val scale: Double,
        ) {
            companion object {
                val Empty: TilePlan = TilePlan(emptyList(), emptyList(), DoubleOffset.Zero, 1.0)
            }
        }
    }

    /**
     * Stable private layer type.
     */
    private companion object {
        val TYPE: ElementType<TiledImageTileLayerElement, Node> =
            ElementType(
                elementClass = TiledImageTileLayerElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(0 < element.destinationSize.width && 0 < element.destinationSize.height) {
                        "Tiled image layer dimensions must be positive."
                    }
                },
                createNode = { element ->
                    Node(element.source, element.bounds, element.levels, element.state, element.destinationSize, element.cachePolicy)
                },
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
