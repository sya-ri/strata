package dev.s7a.strata.component

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.geometry.exactDoubleCenterOrNull
import dev.s7a.strata.geometry.exactDoubleMidpointOrNull
import dev.s7a.strata.geometry.hasExactlyRepresentableDoubleEdges
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caller-owned mutable transform shared by one viewport and independent navigation controls.
 *
 * Center coordinates use the content coordinate space, and zoom is a multiplier over the viewport's [PanZoomFit] scale.
 * One live retained observer may own viewport geometry, while additional observers may read transform changes without publishing geometry.
 * Reads, writes, observation, geometry publication, and observer release are confined to the constructing thread.
 * Observer callbacks may read state and release observers, but synchronous state writes are rejected so every observer sees a publication that still matches [metrics].
 * If an observer fails, the committed metrics remain current, every other still-live observer is attempted, and the first failure escapes with later failures suppressed.
 * The state owns no retained nodes or rendering resources.
 *
 * @param initialCenter requested initial content center, or null to center the first published content bounds.
 * @param initialZoom initial zoom multiplier within [minimumZoom] and [maximumZoom].
 * @property minimumZoom positive finite minimum zoom multiplier.
 * @property maximumZoom positive finite maximum zoom multiplier.
 * @throws IllegalArgumentException when zoom limits or initial values are invalid.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught") // Navigation, coordinate conversion, observation, and viewport geometry form one transform owner; observer callbacks may throw any throwable.
public class PanZoomState(
    initialCenter: DoubleOffset? = null,
    initialZoom: Double = 1.0,
    public val minimumZoom: Double = 1.0,
    public val maximumZoom: Double = 64.0,
) {
    private val ownerThread: Thread = Thread.currentThread()
    private val observers: MutableMap<Any, (PanZoomMetrics) -> Unit> = LinkedHashMap()
    private var geometryOwner: Any? = null
    private var centerRequested: Boolean = initialCenter != null
    private var currentMetrics: PanZoomMetrics
    private var notifyingObservers: Boolean = false

    init {
        require(minimumZoom.isFinite() && 0.0 < minimumZoom) { "Minimum zoom must be finite and positive." }
        require(maximumZoom.isFinite() && minimumZoom <= maximumZoom) { "Maximum zoom must be finite and at least the minimum zoom." }
        require(initialZoom.isFinite() && minimumZoom <= initialZoom && initialZoom <= maximumZoom) {
            "Initial zoom must be finite and within the configured limits."
        }
        currentMetrics =
            PanZoomMetrics(
                center = initialCenter ?: DoubleOffset.Zero,
                zoom = initialZoom,
            )
    }

    /**
     * Current immutable transform and geometry snapshot.
     *
     * @throws IllegalStateException when read from another thread.
     */
    public val metrics: PanZoomMetrics
        get() {
            checkThread()
            return currentMetrics
        }

    /**
     * Moves the viewport center by a displacement in content units.
     *
     * Known geometry clamps the result so content remains centered on axes smaller than the viewport and no larger axis exposes space beyond its bounds.
     * Before geometry is known, the requested center is retained for the first viewport publication.
     *
     * @param delta finite content-coordinate displacement.
     * @return the resulting clamped center.
     * @throws IllegalArgumentException when arithmetic produces a non-finite coordinate.
     * @throws IllegalStateException when called from another thread or synchronously from an observer callback.
     * @throws Throwable when a state observer fails after the new metrics are committed.
     */
    public fun panBy(delta: DoubleOffset): DoubleOffset {
        checkThread()
        return centerOn(currentMetrics.center + delta)
    }

    /**
     * Centers the viewport on a content coordinate.
     *
     * @param position requested finite content coordinate.
     * @return the resulting clamped center.
     * @throws IllegalStateException when called from another thread or synchronously from an observer callback.
     * @throws Throwable when a state observer fails after the new metrics are committed.
     */
    public fun centerOn(position: DoubleOffset): DoubleOffset {
        checkThread()
        checkWritable()
        centerRequested = true
        val next = withCenter(currentMetrics, position)
        publish(next, origin = null)
        return next.center
    }

    /**
     * Multiplies zoom and optionally preserves one viewport-local anchor.
     *
     * Values beyond configured limits are clamped.
     * An anchor is applied only after viewport geometry is known; before that publication, zoom changes while the requested center remains unchanged.
     * Bounds clamping may move the anchored content coordinate when preserving it would expose space beyond content.
     *
     * @param factor positive finite zoom multiplier.
     * @param anchor optional finite viewport-local coordinate to keep over the same content coordinate.
     * @return the resulting clamped zoom multiplier.
     * @throws IllegalArgumentException when [factor] is not finite and positive or the resolved transform cannot remain finite and positive.
     * @throws IllegalStateException when called from another thread or synchronously from an observer callback.
     * @throws Throwable when a state observer fails after the new metrics are committed.
     */
    public fun zoomBy(
        factor: Double,
        anchor: DoubleOffset? = null,
    ): Double {
        checkThread()
        require(factor.isFinite() && 0.0 < factor) { "Zoom factor must be finite and positive." }
        val product = currentMetrics.zoom * factor
        val requested =
            when {
                product.isInfinite() -> maximumZoom
                product == 0.0 -> minimumZoom
                else -> product
            }
        return zoomTo(requested, anchor)
    }

    /**
     * Sets zoom and optionally preserves one viewport-local anchor.
     *
     * Values beyond configured limits are clamped.
     * An anchor is applied only after viewport geometry is known; bounds clamping still takes precedence.
     *
     * @param zoom requested positive finite zoom multiplier.
     * @param anchor optional finite viewport-local coordinate to keep over the same content coordinate.
     * @return the resulting clamped zoom multiplier.
     * @throws IllegalArgumentException when [zoom] is not finite and positive or the resolved transform or anchor arithmetic cannot remain finite and positive.
     * @throws IllegalStateException when called from another thread or synchronously from an observer callback.
     * @throws Throwable when a state observer fails after the new metrics are committed.
     */
    public fun zoomTo(
        zoom: Double,
        anchor: DoubleOffset? = null,
    ): Double {
        checkThread()
        checkWritable()
        require(zoom.isFinite() && 0.0 < zoom) { "Zoom must be finite and positive." }
        val nextZoom = zoom.coerceIn(minimumZoom, maximumZoom)
        val next = withZoom(currentMetrics, nextZoom, anchor)
        publish(next, origin = null)
        return next.zoom
    }

    /**
     * Restores minimum zoom and centers the current content bounds.
     *
     * Before geometry is known, reset forgets an explicit center so the first viewport publication supplies it.
     *
     * @return the resulting metrics snapshot.
     * @throws IllegalArgumentException when the minimum zoom cannot resolve to a finite positive scale for known geometry.
     * @throws IllegalStateException when called from another thread or synchronously from an observer callback.
     * @throws Throwable when a state observer fails after the new metrics are committed.
     */
    public fun reset(): PanZoomMetrics {
        checkThread()
        checkWritable()
        val next =
            if (currentMetrics.geometryKnown) {
                buildMetrics(
                    center = contentCenter(currentMetrics.contentBounds),
                    zoom = minimumZoom,
                    contentBounds = currentMetrics.contentBounds,
                    viewportSize = currentMetrics.viewportSize,
                    fit = currentMetrics.fit,
                )
            } else {
                currentMetrics.copy(center = DoubleOffset.Zero, zoom = minimumZoom)
            }
        centerRequested = currentMetrics.geometryKnown
        publish(next, origin = null)
        return next
    }

    /**
     * Converts a viewport-local coordinate to the current content coordinate space.
     *
     * @param position finite viewport-local coordinate.
     * @return the corresponding finite content coordinate.
     * @throws IllegalStateException when geometry is not known or the caller uses another thread.
     * @throws IllegalArgumentException when conversion produces a non-finite coordinate.
     */
    public fun localToContent(position: DoubleOffset): DoubleOffset {
        checkThread()
        check(currentMetrics.geometryKnown) { "Pan-and-zoom geometry is not known." }
        return localToContent(position, currentMetrics)
    }

    /**
     * Converts a content coordinate to the current viewport-local coordinate space.
     *
     * @param position finite content coordinate.
     * @return the corresponding finite viewport-local coordinate.
     * @throws IllegalStateException when geometry is not known or the caller uses another thread.
     * @throws IllegalArgumentException when conversion produces a non-finite coordinate.
     */
    public fun contentToLocal(position: DoubleOffset): DoubleOffset {
        checkThread()
        check(currentMetrics.geometryKnown) { "Pan-and-zoom geometry is not known." }
        val viewportCenter = viewportCenter(currentMetrics.viewportSize)
        return DoubleOffset(
            (position.x - currentMetrics.center.x) * currentMetrics.scale + viewportCenter.x,
            (position.y - currentMetrics.center.y) * currentMetrics.scale + viewportCenter.y,
        )
    }

    /**
     * Registers one privileged owner-thread observer without immediately invoking it.
     *
     * The returned handle is the identity required for geometry publication and must be closed by its retained owner.
     * Closing the current geometry owner permits another viewport to attach while retaining the last metrics snapshot.
     * This runtime extension contract is opt-in and may evolve between minor releases.
     *
     * @param callback owner-thread invalidation callback receiving each published metrics snapshot except geometry feedback from its own [updateGeometry] call; it may read state and release observers but must not write state synchronously.
     * @return privileged observer identity owned by the caller until closed.
     * @throws IllegalStateException when called from another thread.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (PanZoomMetrics) -> Unit): PanZoomStateObserver {
        checkThread()
        val token = Any()
        observers[token] = callback
        return PanZoomStateObserver(
            token = token,
            release = {
                checkThread()
                val removedCallback = checkNotNull(observers.remove(token)) { "Pan-and-zoom observer was already released." }
                check(removedCallback === callback) { "Pan-and-zoom observer callback identity changed." }
                if (geometryOwner === token) geometryOwner = null
            },
        )
    }

    /**
     * Publishes positive content and viewport geometry from one privileged retained viewport.
     *
     * One observer may own geometry at a time, and feedback to that origin is suppressed because its active measure already observes the new snapshot.
     * A source or size replacement may update geometry through the same observer.
     * This runtime extension contract is opt-in and may evolve between minor releases.
     *
     * @param contentBounds positive half-open content rectangle whose four edges and midpoint are exactly representable in the double coordinate space.
     * @param viewportSize positive logical viewport size.
     * @param fit base scale policy.
     * @param origin live observer that owns this geometry.
     * @throws IllegalArgumentException when geometry is empty, its bound edges or axis midpoints are not exactly representable, or it cannot produce a finite positive transform.
     * @throws IllegalStateException when the observer is released, another viewport owns geometry, the caller uses another thread, or an observer callback synchronously writes state.
     * @throws Throwable when a different state observer fails after the new geometry is committed.
     */
    @InternalStrataRuntimeApi
    public fun updateGeometry(
        contentBounds: LongRect,
        viewportSize: IntSize,
        fit: PanZoomFit,
        origin: PanZoomStateObserver,
    ) {
        checkThread()
        checkWritable()
        check(observers.containsKey(origin.token)) { "Pan-and-zoom geometry requires a live observer." }
        val existingOwner = geometryOwner
        check(existingOwner == null || existingOwner === origin.token) { "Pan-and-zoom state already belongs to another viewport." }
        require(0L < contentBounds.width && 0L < contentBounds.height) { "Pan-and-zoom content bounds must be positive." }
        require(0 < viewportSize.width && 0 < viewportSize.height) { "Pan-and-zoom viewport size must be positive." }
        validateDoubleBounds(contentBounds)
        val requestedCenter = if (centerRequested) currentMetrics.center else contentCenter(contentBounds)
        val next = buildMetrics(requestedCenter, currentMetrics.zoom, contentBounds, viewportSize, fit)
        geometryOwner = origin.token
        centerRequested = true
        publish(next, origin.token)
    }

    private fun withCenter(
        metrics: PanZoomMetrics,
        center: DoubleOffset,
    ): PanZoomMetrics =
        if (metrics.geometryKnown) {
            buildMetrics(center, metrics.zoom, metrics.contentBounds, metrics.viewportSize, metrics.fit)
        } else {
            metrics.copy(center = center)
        }

    private fun withZoom(
        metrics: PanZoomMetrics,
        zoom: Double,
        anchor: DoubleOffset?,
    ): PanZoomMetrics {
        if (metrics.geometryKnown.not()) return metrics.copy(zoom = zoom)
        val anchoredCenter =
            if (anchor == null) {
                metrics.center
            } else {
                val anchorContent = localToContent(anchor, metrics)
                val nextScale = resolveScale(metrics.contentBounds, metrics.viewportSize, metrics.fit, zoom)
                val localCenter = viewportCenter(metrics.viewportSize)
                DoubleOffset(
                    anchorContent.x - (anchor.x - localCenter.x) / nextScale,
                    anchorContent.y - (anchor.y - localCenter.y) / nextScale,
                )
            }
        return buildMetrics(anchoredCenter, zoom, metrics.contentBounds, metrics.viewportSize, metrics.fit)
    }

    private fun buildMetrics(
        center: DoubleOffset,
        zoom: Double,
        contentBounds: LongRect,
        viewportSize: IntSize,
        fit: PanZoomFit,
    ): PanZoomMetrics {
        val scale = resolveScale(contentBounds, viewportSize, fit, zoom)
        return PanZoomMetrics(
            center = clampCenter(center, contentBounds, viewportSize, scale),
            zoom = zoom,
            scale = scale,
            viewportSize = viewportSize,
            contentBounds = contentBounds,
            fit = fit,
            geometryKnown = true,
        )
    }

    private fun publish(
        next: PanZoomMetrics,
        origin: Any?,
    ) {
        if (next == currentMetrics) return
        currentMetrics = next
        notifyingObservers = true
        var failure: Throwable? = null
        try {
            observers.toList().forEach { (token, callback) ->
                failure = combineObserverFailure(failure, notifyObserver(token, callback, next, origin))
            }
        } finally {
            notifyingObservers = false
        }
        failure?.let { throw it }
    }

    private fun notifyObserver(
        token: Any,
        callback: (PanZoomMetrics) -> Unit,
        next: PanZoomMetrics,
        origin: Any?,
    ): Throwable? {
        if (token === origin || observers[token] !== callback) return null
        return runCatching { callback(next) }.exceptionOrNull()
    }

    private fun combineObserverFailure(
        previous: Throwable?,
        next: Throwable?,
    ): Throwable? {
        if (next == null) return previous
        if (previous == null) return next
        if (previous !== next) previous.addSuppressed(next)
        return previous
    }

    private fun checkWritable() {
        check(notifyingObservers.not()) { "Pan-and-zoom state cannot be written synchronously from an observer callback." }
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "Pan-and-zoom state requires its creator thread." }
    }

    private companion object {
        fun buildAxisCenter(
            requested: Double,
            minimum: Long,
            maximum: Long,
            viewportExtent: Int,
            scale: Double,
        ): Double {
            val minimumDouble = minimum.toDouble()
            val maximumDouble = maximum.toDouble()
            val contentExtent = maximumDouble - minimumDouble
            val visibleExtent = viewportExtent.toDouble() / scale
            if (contentExtent <= visibleExtent) {
                return checkNotNull(exactDoubleMidpointOrNull(minimum, maximum)) {
                    "Validated pan-and-zoom bounds must retain an exact axis midpoint."
                }
            }
            val halfVisible = visibleExtent / 2.0
            return requested.coerceIn(minimumDouble + halfVisible, maximumDouble - halfVisible)
        }

        fun clampCenter(
            requested: DoubleOffset,
            bounds: LongRect,
            viewportSize: IntSize,
            scale: Double,
        ): DoubleOffset =
            DoubleOffset(
                buildAxisCenter(requested.x, bounds.left, bounds.right, viewportSize.width, scale),
                buildAxisCenter(requested.y, bounds.top, bounds.bottom, viewportSize.height, scale),
            )

        fun contentCenter(bounds: LongRect): DoubleOffset = checkNotNull(bounds.exactDoubleCenterOrNull()) { "Validated pan-and-zoom bounds must retain an exact double-coordinate midpoint." }

        fun localToContent(
            position: DoubleOffset,
            metrics: PanZoomMetrics,
        ): DoubleOffset {
            val localCenter = viewportCenter(metrics.viewportSize)
            return DoubleOffset(
                metrics.center.x + (position.x - localCenter.x) / metrics.scale,
                metrics.center.y + (position.y - localCenter.y) / metrics.scale,
            )
        }

        fun resolveScale(
            bounds: LongRect,
            viewportSize: IntSize,
            fit: PanZoomFit,
            zoom: Double,
        ): Double {
            val horizontal = viewportSize.width.toDouble() / bounds.width.toDouble()
            val vertical = viewportSize.height.toDouble() / bounds.height.toDouble()
            val base =
                when (fit) {
                    PanZoomFit.Contain -> minOf(horizontal, vertical)
                    PanZoomFit.Cover -> maxOf(horizontal, vertical)
                }
            val resolved = base * zoom
            require(resolved.isFinite() && 0.0 < resolved) { "Pan-and-zoom geometry must resolve to a finite positive scale." }
            return resolved
        }

        fun validateDoubleBounds(bounds: LongRect) {
            require(bounds.hasExactlyRepresentableDoubleEdges() && bounds.exactDoubleCenterOrNull() != null) {
                "Pan-and-zoom content-bound edges and midpoint must be exactly representable in the double coordinate space."
            }
        }

        fun viewportCenter(size: IntSize): DoubleOffset = DoubleOffset(size.width / 2.0, size.height / 2.0)
    }
}
