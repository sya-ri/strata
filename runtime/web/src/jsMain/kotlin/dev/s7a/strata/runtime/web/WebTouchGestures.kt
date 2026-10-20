@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerGesture
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.hypot

/**
 * Recognizes one bounded two-pointer navigation sequence on the browser agent.
 * A tap is dispatched only after release; movement never synthesizes an activation press.
 * Capture and positions belong to this host and are discarded on cancellation or close.
 */
internal class WebTouchGestures(
    private val root: HTMLElement,
    private val session: RuntimeUiSession,
    private val tap: (IntOffset) -> Unit,
) : AutoCloseable {
    private val positions = LinkedHashMap<Int, IntOffset>()
    private var origin: IntOffset? = null
    private var navigating = false

    /**
     * Decodes native touch pointers and returns true when this recognizer owns their delivery.
     * Native editors bypass this recognizer so the browser retains text selection and keyboard behavior.
     */
    fun handle(
        event: Event,
        position: IntOffset,
    ): Boolean {
        if (mapOf("touch" to true)[event.asDynamic().pointerType as? String] != true) return false
        val id = event.asDynamic().pointerId as? Int ?: return false
        event.preventDefault()
        when (WebDomEvent.decode(event.type)) {
            WebDomEvent.PointerDown -> begin(id, position, event.isTrusted)
            WebDomEvent.PointerMove -> move(id, position)
            WebDomEvent.PointerUp -> end(id, position)
            else -> Unit
        }
        return true
    }

    override fun close() {
        val ids = positions.keys.toList()
        positions.clear()
        origin = null
        navigating = false
        ids.forEach { id ->
            if (root.asDynamic().hasPointerCapture(id) as Boolean) root.asDynamic().releasePointerCapture(id)
        }
    }

    private fun begin(
        id: Int,
        position: IntOffset,
        trusted: Boolean,
    ) {
        if (positions.size == 2) return
        positions[id] = position
        if (positions.size == 1) {
            origin = position
            navigating = false
        } else {
            origin = center(positions.values.toList())
            navigating = true
        }
        if (trusted) root.asDynamic().setPointerCapture(id)
    }

    private fun move(
        id: Int,
        position: IntOffset,
    ) {
        val previous = positions[id] ?: return
        val before = positions.values.toList()
        positions[id] = position
        val at = checkNotNull(origin)
        if (positions.size == 2) {
            pinch(before, positions.values.toList(), at)
            return
        }
        val wasNavigating = navigating
        if (navigating.not()) navigating = 6.0 <= distance(at, position)
        if (navigating) {
            val from = if (wasNavigating) previous else at
            session.dispatchPointerGesture(PointerGesture.Pan(at, (position.x - from.x).toDouble(), (position.y - from.y).toDouble()))
        }
    }

    private fun pinch(
        before: List<IntOffset>,
        after: List<IntOffset>,
        at: IntOffset,
    ) {
        val oldCenter = center(before)
        val newCenter = center(after)
        session.dispatchPointerGesture(PointerGesture.Pan(at, (newCenter.x - oldCenter.x).toDouble(), (newCenter.y - oldCenter.y).toDouble()))
        val oldDistance = distance(before[0], before[1])
        val newDistance = distance(after[0], after[1])
        if (0.0 < oldDistance && 0.0 < newDistance) session.dispatchPointerGesture(PointerGesture.Zoom(at, newDistance / oldDistance))
    }

    private fun end(
        id: Int,
        position: IntOffset,
    ) {
        if (positions.remove(id) == null) return
        if (positions.isEmpty()) {
            if (navigating.not()) tap(position)
            origin = null
            navigating = false
        } else {
            origin = positions.values.first()
        }
    }

    private fun center(points: List<IntOffset>): IntOffset =
        IntOffset(
            (points[0].x.toLong() + points[1].x).div(2).toInt(),
            (points[0].y.toLong() + points[1].y).div(2).toInt(),
        )

    private fun distance(
        first: IntOffset,
        second: IntOffset,
    ): Double = hypot(first.x.toDouble() - second.x, first.y.toDouble() - second.y)
}
