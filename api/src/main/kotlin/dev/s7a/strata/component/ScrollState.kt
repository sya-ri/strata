package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caller-owned mutable position shared by a scroll area and zero or more independent scrollbars.
 *
 * Reads, writes, runtime observation, and subscription release are confined to the constructing thread.
 * Area attachment publishes viewport and content geometry, while application calls may request positions before or after attachment.
 * Requested positions are clamped whenever current geometry is known.
 * The state owns no retained nodes and does not own its observers.
 *
 * @param initialOffset initial non-negative finite logical displacement.
 */
public class ScrollState(
    initialOffset: Double = 0.0,
) {
    private val ownerThread = Thread.currentThread()
    private val observers: MutableMap<Any, (ScrollMetrics) -> Unit> = LinkedHashMap()
    private var currentMetrics = ScrollMetrics(offset = validateOffset(initialOffset))
    private var geometryKnown = false

    /**
     * Current immutable geometry and position snapshot.
     */
    public val metrics: ScrollMetrics
        get() {
            checkThread()
            return currentMetrics
        }

    /**
     * Moves by [delta] logical pixels and returns the resulting offset.
     *
     * Non-finite deltas are rejected without changing state.
     */
    public fun scrollBy(delta: Double): Double {
        checkThread()
        require(delta.isFinite()) { "Scroll delta must be finite." }
        return setOffset(currentMetrics.offset + delta, origin = null)
    }

    /**
     * Moves to [offset] logical pixels and returns the clamped resulting offset.
     */
    public fun scrollTo(offset: Double): Double {
        checkThread()
        return setOffset(validateOffset(offset), origin = null)
    }

    /**
     * Installs one runtime observer and returns its owner-thread idempotent release handle.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (ScrollMetrics) -> Unit): ScrollStateObserver {
        checkThread()
        val token = Any()
        observers[token] = callback
        return ScrollStateObserver(
            token = token,
            release = {
                checkThread()
                observers.remove(token)
            },
        )
    }

    /**
     * Publishes measured geometry while suppressing feedback to the originating area observer.
     */
    @InternalStrataRuntimeApi
    public fun updateGeometry(
        viewportExtent: Int,
        contentExtent: Int,
        origin: ScrollStateObserver,
    ) {
        checkThread()
        require(0 <= viewportExtent) { "Scroll viewport extent must be non-negative." }
        require(0 <= contentExtent) { "Scroll content extent must be non-negative." }
        geometryKnown = true
        val maximum = maxOf(0, contentExtent - viewportExtent).toDouble()
        val next = ScrollMetrics(currentMetrics.offset.coerceAtMost(maximum), viewportExtent, contentExtent)
        publish(next, origin.token)
    }

    /**
     * Moves from a runtime input handler while suppressing feedback to its originating node.
     */
    @InternalStrataRuntimeApi
    public fun scrollBy(
        delta: Double,
        origin: ScrollStateObserver,
    ): Double {
        checkThread()
        require(delta.isFinite()) { "Scroll delta must be finite." }
        return setOffset(currentMetrics.offset + delta, origin.token)
    }

    /**
     * Moves from a runtime input handler while suppressing feedback to its originating node.
     */
    @InternalStrataRuntimeApi
    public fun scrollTo(
        offset: Double,
        origin: ScrollStateObserver,
    ): Double {
        checkThread()
        return setOffset(validateOffset(offset), origin.token)
    }

    private fun setOffset(
        offset: Double,
        origin: Any?,
    ): Double {
        val nextOffset = if (geometryKnown) offset.coerceIn(0.0, currentMetrics.maximumOffset) else offset
        publish(currentMetrics.copy(offset = nextOffset), origin)
        return nextOffset
    }

    private fun publish(
        next: ScrollMetrics,
        origin: Any?,
    ) {
        if (next == currentMetrics) return
        currentMetrics = next
        observers.forEach { (token, callback) ->
            if (token !== origin) callback(next)
        }
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "Scroll state requires its creator thread." }
    }

    private companion object {
        fun validateOffset(value: Double): Double {
            require(value.isFinite() && 0.0 <= value) { "Scroll offset must be finite and non-negative." }
            return value
        }
    }
}
