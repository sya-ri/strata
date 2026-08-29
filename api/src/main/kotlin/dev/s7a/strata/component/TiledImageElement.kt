@file:OptIn(InternalStrataRuntimeApi::class)
@file:Suppress("LongParameterList")

package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.geometry.exactDoubleCenterOrNull
import dev.s7a.strata.geometry.hasExactlyRepresentableDoubleEdges
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import kotlin.math.roundToLong
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for one tiled raster viewport and its content-positioned overlays.
 *
 * The first private child owns tile observations and paint commands, while the remaining logical children are application overlays.
 * Keeping those retained nodes separate prevents overlay-only reconciliation from invalidating tile paint.
 *
 * @param source externally owned immutable source identity.
 * @param bounds source bounds snapshotted during declaration.
 * @param levels immutable source level snapshot.
 * @param state caller-owned transform.
 * @param destinationSize exact viewport extent.
 * @param fit base fit policy.
 * @param cachePolicy bounded tile working-set policy.
 * @param modifier active behavior around the viewport.
 * @param key optional stable sibling identity.
 * @param overlays direct fixed-size overlay descriptions.
 */
internal class TiledImageElement private constructor(
    private val source: TiledImageSource,
    private val bounds: LongRect,
    private val levels: List<TiledImageLevel>,
    private val state: PanZoomState,
    private val destinationSize: IntSize,
    private val fit: PanZoomFit,
    private val cachePolicy: TiledImageCachePolicy,
    modifier: Modifier,
    key: ElementKey<*>?,
    overlays: List<Element>,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = listOf(TiledImageTileLayerElement(source, bounds, levels, state, destinationSize, cachePolicy)) + overlays,
        modifier = modifier,
    ) {
    /**
     * Retained clipped overlay layout and sole owner of the transform's viewport geometry.
     *
     * @param bounds initial source content bounds.
     * @param state initial caller-owned transform.
     * @param destinationSize initial exact viewport size.
     * @param fit initial fit policy.
     */
    private class Node(
        private var source: TiledImageSource,
        private var bounds: LongRect,
        private var levels: List<TiledImageLevel>,
        private var state: PanZoomState?,
        private var destinationSize: IntSize,
        private var fit: PanZoomFit,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        ClipChildrenNode,
        SessionAttachmentNode {
        private var active: Boolean = false
        private var observer: PanZoomStateObserver? = null

        override fun attach() {
            sessionAttached()
        }

        override fun sessionAttached() {
            if (active) return
            active = true
            observer = observeState(checkNotNull(state))
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun sessionDetached() {
            active = false
            val previous = observer
            observer = null
            previous?.close()
        }

        override fun detach() {
            sessionDetached()
        }

        override fun dispose() {
            state = null
            sessionDetached()
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.isSatisfiedBy(destinationSize)) { "Tiled image constraints must contain its requested size." }
            check(1 <= scope.childCount) { "Tiled image requires its retained tile layer." }
            checkNotNull(state).updateGeometry(
                contentBounds = bounds,
                viewportSize = destinationSize,
                fit = fit,
                origin = checkNotNull(observer),
            )
            scope.measureChild(0, Constraints.fixed(destinationSize.width, destinationSize.height))
            val overlayConstraints =
                Constraints(
                    minWidth = 0,
                    maxWidth = destinationSize.width,
                    minHeight = 0,
                    maxHeight = destinationSize.height,
                )
            for (index in 1 until scope.childCount) {
                scope.measureChild(index, overlayConstraints)
            }
            return destinationSize
        }

        override fun layout(scope: LayoutScope) {
            check(1 <= scope.childCount) { "Tiled image requires its measured tile layer." }
            scope.placeChild(0, IntOffset.Zero)
            val currentState = checkNotNull(state)
            for (index in 1 until scope.childCount) {
                val placement =
                    checkNotNull(scope.childParentData(index, TiledImageContentParentData.KEY)) {
                        "Every tiled image overlay must use atContentPosition."
                    }
                val local = currentState.contentToLocal(placement.position)
                val childSize = scope.measuredChildSize(index)
                scope.placeChild(index, alignedOffset(local, childSize, placement.alignment))
            }
        }

        /**
         * Applies viewport geometry and state changes after reconciliation.
         *
         * @param current incoming immutable description.
         * @return measurement invalidation for geometry or state changes, otherwise no invalidation.
         */
        internal fun update(current: TiledImageElement): DirtyMask {
            val sourceChanged = source !== current.source
            val geometryChanged =
                bounds != current.bounds ||
                    levels != current.levels ||
                    destinationSize != current.destinationSize ||
                    fit != current.fit
            val sourceGeometryChanged = bounds != current.bounds || levels != current.levels
            check(sourceChanged || sourceGeometryChanged.not()) {
                "Tiled image geometry cannot change without replacing its source identity."
            }
            val stateChanged = state !== current.state
            source = current.source
            bounds = current.bounds
            levels = current.levels
            destinationSize = current.destinationSize
            fit = current.fit
            if (stateChanged) {
                val previous = observer
                observer = null
                state = current.state
                previous?.close()
                if (active) observer = observeState(current.state)
            }
            return if (geometryChanged || stateChanged) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }

        private fun observeState(observed: PanZoomState): PanZoomStateObserver =
            observed.observe {
                invalidate(DirtyMask.of(DirtyPhase.Layout))
            }

        private fun alignedOffset(
            anchor: DoubleOffset,
            childSize: IntSize,
            alignment: Alignment,
        ): IntOffset {
            val x =
                when (alignment.horizontalAlignment) {
                    HorizontalAlignment.Start -> anchor.x
                    HorizontalAlignment.Center -> anchor.x - childSize.width / 2.0
                    HorizontalAlignment.End -> anchor.x - childSize.width.toDouble()
                }
            val y =
                when (alignment.verticalAlignment) {
                    VerticalAlignment.Top -> anchor.y
                    VerticalAlignment.Center -> anchor.y - childSize.height / 2.0
                    VerticalAlignment.Bottom -> anchor.y - childSize.height.toDouble()
                }
            return IntOffset(
                saturateOutsideViewport(x, childSize.width, destinationSize.width),
                saturateOutsideViewport(y, childSize.height, destinationSize.height),
            )
        }

        private fun saturateOutsideViewport(
            value: Double,
            childExtent: Int,
            viewportExtent: Int,
        ): Int {
            val minimum = -childExtent.toDouble()
            if (value <= minimum) return -childExtent
            if (viewportExtent.toDouble() <= value) return viewportExtent
            return value.roundToLong().toInt()
        }
    }

    /**
     * Stable component type and declaration boundary.
     */
    internal companion object {
        private val TYPE: ElementType<TiledImageElement, Node> =
            ElementType(
                elementClass = TiledImageElement::class,
                nodeClass = Node::class,
                validateLocal = { element -> validateGeometry(element.bounds, element.levels, element.destinationSize) },
                createNode = { element ->
                    Node(element.source, element.bounds, element.levels, element.state, element.destinationSize, element.fit)
                },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Snapshots and validates one source geometry before constructing retained descriptions.
         *
         * @param source externally owned immutable source generation.
         * @param state caller-owned transform.
         * @param destinationSize exact viewport extent.
         * @param fit base fit policy.
         * @param cachePolicy bounded working-set policy.
         * @param modifier active viewport behavior.
         * @param key optional stable sibling identity.
         * @param overlays immutable direct overlay snapshot.
         * @return one immutable tiled-image element.
         */
        internal fun create(
            source: TiledImageSource,
            state: PanZoomState,
            destinationSize: IntSize,
            fit: PanZoomFit,
            cachePolicy: TiledImageCachePolicy,
            modifier: Modifier,
            key: ElementKey<*>?,
            overlays: List<Element>,
        ): Element {
            val bounds = source.bounds
            val levels = Collections.unmodifiableList(source.levels.toList())
            validateGeometry(bounds, levels, destinationSize)
            return TiledImageElement(source, bounds, levels, state, destinationSize, fit, cachePolicy, modifier, key, overlays)
        }

        private fun validateGeometry(
            bounds: LongRect,
            levels: List<TiledImageLevel>,
            destinationSize: IntSize,
        ) {
            require(0 < destinationSize.width && 0 < destinationSize.height) { "Tiled image viewport dimensions must be positive." }
            require(0L < bounds.width && 0L < bounds.height) { "Tiled image content bounds must be positive." }
            require(bounds.hasExactlyRepresentableDoubleEdges() && bounds.exactDoubleCenterOrNull() != null) {
                "Tiled image bound edges and midpoint must be exactly representable in the double coordinate space."
            }
            require(levels.isEmpty().not()) { "Tiled image sources require at least one level." }
            var previous: TiledImageLevel? = null
            levels.forEach { level ->
                val previousLevel = previous
                if (previousLevel != null) {
                    require(previousLevel.contentUnitsPerPixel < level.contentUnitsPerPixel) {
                        "Tiled image levels must be ordered from finest to coarsest."
                    }
                    val previousWidth = contentWidth(previousLevel)
                    val previousHeight = contentHeight(previousLevel)
                    val currentWidth = contentWidth(level)
                    val currentHeight = contentHeight(level)
                    require(previousWidth <= currentWidth && currentWidth % previousWidth == 0L) {
                        "Coarser tiled image widths must be aligned multiples of finer widths."
                    }
                    require(previousHeight <= currentHeight && currentHeight % previousHeight == 0L) {
                        "Coarser tiled image heights must be aligned multiples of finer heights."
                    }
                }
                validateEdgeGrid(bounds.left, bounds.right, contentWidth(level))
                validateEdgeGrid(bounds.top, bounds.bottom, contentHeight(level))
                previous = level
            }
        }

        private fun contentWidth(level: TiledImageLevel): Long = Math.multiplyExact(level.tilePixelSize.width.toLong(), level.contentUnitsPerPixel)

        private fun contentHeight(level: TiledImageLevel): Long = Math.multiplyExact(level.tilePixelSize.height.toLong(), level.contentUnitsPerPixel)

        private fun validateEdgeGrid(
            minimum: Long,
            maximum: Long,
            tileExtent: Long,
        ) {
            val first = Math.floorDiv(minimum, tileExtent)
            val last = Math.floorDiv(Math.subtractExact(maximum, 1L), tileExtent)
            Math.multiplyExact(first, tileExtent)
            Math.addExact(Math.multiplyExact(last, tileExtent), tileExtent)
        }
    }
}
