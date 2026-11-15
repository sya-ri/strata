package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerGesture
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DragScrollNode
import dev.s7a.strata.node.HorizontalDragNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode
import kotlin.math.abs

/**
 * Dispatches pointer events through laid-out retained nodes and owns one captured gesture.
 *
 * The enclosing tree serializes every operation on its owner thread.
 * One capture holds its owner and button, plus a pending hit or drag recognizer for touch scrolling.
 * One released fling retains only its viewport and bounded motion until interruption, completion, or cleanup.
 * Callback failures propagate unchanged after the capture reference has been cleared when appropriate.
 */
@Suppress("TooManyFunctions") // Pointer capture, gesture hit testing and cleanup share one retained input owner.
internal class InputPipeline(
    private val focusedInputPipeline: FocusedInputPipeline,
    private val synchronizeGeometry: (RetainedNode) -> Unit,
) {
    private var capture: Capture? = null
    private var momentum: Momentum? = null
    private var frameTime: FrameTime? = null
    private var frameSeconds = 0.0

    /**
     * Advances kinetic input before the owning session applies its next frame cutoff.
     */
    fun advanceScroll(
        root: RetainedNode?,
        time: FrameTime,
    ) {
        val previous = frameTime
        frameTime = time
        frameSeconds = if (previous != null) secondsBetween(previous.nanoseconds, time.nanoseconds).coerceAtLeast(0.0) else 0.0
        val current = momentum ?: return
        if (root == null) {
            momentum = null
            return
        }
        synchronizeGeometry(root)
        val input = current.owner.node as DragScrollNode
        val invalidOwner = containsPlaced(root.effectiveRoot, current.owner).not() || scrollIdentityChanged(current.owner, current.identity)
        if (invalidOwner) {
            stopMomentum()
            return
        }
        val options = input.dragScrollOptions
        val inactive = input.canDragScroll.not() || options.enabled.not() || options.inertiaEnabled.not()
        if (inactive) {
            momentum = null
            return
        }
        val delta = current.motion.advance(time.nanoseconds)
        val consumed = if (delta != 0.0) input.scrollByDrag(delta) else 0.0
        check(consumed.isFinite()) { "Drag scrolling must return a finite consumed displacement." }
        if (current.motion.active.not() || SCROLL_EPSILON < abs(consumed - delta)) momentum = null
    }

    /**
     * Stops kinetic navigation when another navigation input takes over.
     */
    fun stopMomentum() {
        momentum = null
    }

    /**
     * Hit-tests one recognized navigation gesture without pointer activation or focus acquisition.
     */
    fun dispatchGesture(
        root: RetainedNode,
        gesture: PointerGesture,
    ): InputResult {
        stopMomentum()
        return dispatchGestureNode(root.effectiveRoot, gesture, ancestorAllowsHit = true)
    }

    private fun dispatchGestureNode(
        retained: RetainedEntry,
        gesture: PointerGesture,
        ancestorAllowsHit: Boolean,
    ): InputResult {
        val descendantsAllowHit = ancestorAllowsHit && ((retained.node is ClipChildrenNode).not() || retained.contains(gesture.position))
        if (descendantsAllowHit) {
            for (index in (0 until retained.effectiveChildCount).reversed()) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) {
                    val result = dispatchGestureNode(child, gesture, ancestorAllowsHit = true)
                    if (result == InputResult.Consumed) return result
                }
            }
        }
        val input = retained.node as? PointerInputNode ?: return InputResult.Ignored
        if (ancestorAllowsHit.not() || retained.contains(gesture.position).not()) return InputResult.Ignored
        val localGesture =
            when (gesture) {
                is PointerGesture.Pan -> gesture.copy(deltaX = gesture.deltaX / retained.localToTree.scale, deltaY = gesture.deltaY / retained.localToTree.scale)
                is PointerGesture.Zoom -> gesture
            }
        return input.onPointerGesture(localGesture, retained.localToTree.localPosition(gesture.position))
    }

    /**
     * Dispatches [event] deepest and topmost first.
     *
     * @param root the laid-out retained root.
     * @param event the tree-coordinate event.
     * @return consumed when a node handles the event, otherwise ignored.
     */
    fun dispatch(
        root: RetainedNode,
        event: PointerEvent,
    ): InputResult {
        when (event) {
            is PointerEvent.Move -> updateHover(root.effectiveRoot, event.position)

            is PointerEvent.Drag -> updateHover(root.effectiveRoot, event.position)

            is PointerEvent.Press,
            is PointerEvent.Release,
            is PointerEvent.Scroll,
            -> Unit
        }
        val stopped = momentum
        if (event is PointerEvent.Press || event is PointerEvent.Scroll) stopMomentum()
        val captured = capture
        if (captured != null && captured.accepts(event)) {
            if (captured.scroll != null) return dispatchScroll(root, captured, event)
            if (event is PointerEvent.Release) capture = null
            val input = captured.owner.node as PointerCaptureNode
            input.onPointerEvent(eventFor(captured.owner, event), localPosition(captured.owner, event))
            return InputResult.Consumed
        }
        if (capture == null && event is PointerEvent.Press && event.button === PointerButton.Primary) {
            if (beginScroll(root, event, stopped?.owner)) return InputResult.Consumed
        }
        return dispatchDirect(root, event)
    }

    private fun beginScroll(
        root: RetainedNode,
        press: PointerEvent.Press,
        stopped: RetainedEntry?,
    ): Boolean {
        val hit = inputHit(root.effectiveRoot, press.position, true) ?: return false
        val viewport = scrollAncestor(hit, press.position) ?: return false
        val input = viewport.node as DragScrollNode
        capture = Capture(viewport, press.button, ScrollGesture(input.dragScrollIdentity, input.dragScrollOptions, press, hit, frameTime?.nanoseconds, stopped !== viewport))
        return true
    }

    private fun dispatchDirect(
        root: RetainedNode,
        event: PointerEvent,
    ): InputResult {
        if (event is PointerEvent.Press && event.button === PointerButton.Primary) {
            focusedInputPipeline.acquireFromPointer(root, event.position)
        }
        return dispatchNode(root.effectiveRoot, event, ancestorAllowsHit = true)
    }

    private fun dispatchScroll(
        root: RetainedNode,
        captured: Capture,
        event: PointerEvent,
    ): InputResult {
        val gesture = checkNotNull(captured.scroll)
        val scale = captured.owner.localToTree.scale
        val input = captured.owner.node as DragScrollNode
        if (event is PointerEvent.Release) {
            releaseScroll(root, captured, event)
        } else {
            when (val drag = gesture.drag(event.position, scale, frameTime?.nanoseconds)) {
                is ScrollGesture.Drag.Scroll -> {
                    input.scrollByDrag(drag.delta)
                }

                is ScrollGesture.Drag.Horizontal -> {
                    capture = null
                    if (containsPlaced(root.effectiveRoot, drag.hit) && drag.hit.contains(drag.press.position)) {
                        dispatchDirect(root, drag.press)
                        synchronizeGeometry(root)
                        dispatch(
                            root,
                            PointerEvent.Drag(
                                event.position,
                                captured.button,
                                event.position.x.toDouble() - drag.press.position.x,
                                event.position.y.toDouble() - drag.press.position.y,
                            ),
                        )
                    }
                }

                null -> {}
            }
        }
        return InputResult.Consumed
    }

    private fun releaseScroll(
        root: RetainedNode,
        captured: Capture,
        event: PointerEvent.Release,
    ) {
        capture = null
        val gesture = checkNotNull(captured.scroll)
        val input = captured.owner.node as DragScrollNode
        when (val release = gesture.release(event.position, captured.owner.localToTree.scale, frameTime?.nanoseconds, frameSeconds)) {
            is ScrollGesture.Release.Tap -> {
                if (inputHit(root.effectiveRoot, event.position, true) === release.hit && captured.owner.contains(event.position)) {
                    dispatchDirect(root, release.press)
                    synchronizeGeometry(root)
                    dispatch(root, event)
                }
            }

            is ScrollGesture.Release.Momentum -> {
                val time = frameTime?.nanoseconds
                if (time != null && input.canDragScroll && input.dragScrollOptions.inertiaEnabled) {
                    val motion = ScrollMomentum(release.velocity, time, input.dragScrollOptions.deceleration)
                    if (motion.active) momentum = Momentum(captured.owner, input.dragScrollIdentity, motion)
                }
            }

            ScrollGesture.Release.Cancelled -> {}
        }
    }

    private fun inputHit(
        retained: RetainedEntry,
        position: IntOffset,
        ancestorAllowsHit: Boolean,
    ): RetainedEntry? {
        val descendantsAllowHit = ancestorAllowsHit && ((retained.node is ClipChildrenNode).not() || retained.contains(position))
        if (descendantsAllowHit) {
            for (index in (0 until retained.effectiveChildCount).reversed()) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) inputHit(child, position, true)?.let { return it }
            }
        }
        return retained.takeIf { ancestorAllowsHit && it.contains(position) && it.node is PointerInputNode }
    }

    private fun scrollAncestor(
        hit: RetainedEntry,
        position: IntOffset,
    ): RetainedEntry? {
        if (hit.node is PointerCaptureNode && hit.node !is HorizontalDragNode) return null
        var entry: RetainedEntry? = hit
        var fallback: RetainedEntry? = null
        while (entry != null) {
            val input = entry.node as? DragScrollNode
            if (input != null && input.dragScrollOptions.enabled && entry.contains(position)) {
                if (fallback == null) fallback = entry
                if (input.canDragScroll) return entry
            }
            entry = entry.parent
        }
        return fallback
    }

    /**
     * Cancels capture when its owner no longer participates in committed layout.
     *
     * @param root current logical root after all placement has completed on the tree owner thread.
     * @throws Throwable when the cancelled owner rejects its notification; capture is already cleared.
     */
    fun layoutCommitted(root: RetainedNode) {
        val captured = capture
        if (captured != null && (containsPlaced(root.effectiveRoot, captured.owner).not() || scrollIdentityChanged(captured.owner, captured.scroll?.identity))) cancelCapture()
        val moving = momentum
        if (moving != null && (containsPlaced(root.effectiveRoot, moving.owner).not() || scrollIdentityChanged(moving.owner, moving.identity))) stopMomentum()
    }

    private fun scrollIdentityChanged(
        owner: RetainedEntry,
        identity: Any?,
    ): Boolean = identity != null && (owner.node as DragScrollNode).dragScrollIdentity !== identity

    /**
     * Cancels capture before one retained component or modifier starts its lifecycle cleanup.
     *
     * @param entry owner-thread entry whose callbacks and resources have not yet been disposed.
     * @throws Throwable when cancellation fails; the caller must continue remaining lifecycle cleanup.
     */
    fun entryWillCleanup(entry: RetainedEntry) {
        val captured = capture
        if (captured?.owner === entry || captured?.scroll?.references(entry) == true) cancelCapture()
        if (momentum?.owner === entry) stopMomentum()
    }

    /**
     * Clears the retained capture reference before notifying its previous owner once.
     *
     * This owner-thread operation is a no-op without capture and does not dispose or otherwise retain the node.
     *
     * @throws Throwable when the captured owner's cancellation callback fails.
     */
    fun cancelCapture() {
        stopMomentum()
        val captured = capture ?: return
        capture = null
        if (captured.scroll == null) (captured.owner.node as PointerCaptureNode).onPointerCaptureCancelled(captured.button)
    }

    /**
     * Clears hover before a retained session detaches or resets input, attempting every retained observer.
     *
     * Previously entered observers may now be unplaced, so this reset traverses them without changing ordinary hit testing.
     *
     * @param root the installed retained root whose hover state is being reset.
     * @throws Throwable when a hover callback rejects the exit transition.
     */
    fun clearHover(root: RetainedNode) {
        val failures = FailureAccumulator()
        visitHover(root.effectiveRoot, respectClips = false, includeUnplaced = true, failures = failures) { _ -> false }
        failures.throwIfPresent()
    }

    private fun updateHover(
        root: RetainedEntry,
        position: IntOffset,
    ) {
        visitHover(root, ancestorAllowsHit = true) { retained -> retained.contains(position) }
    }

    private fun visitHover(
        retained: RetainedEntry,
        ancestorAllowsHit: Boolean = true,
        respectClips: Boolean = true,
        includeUnplaced: Boolean = false,
        failures: FailureAccumulator? = null,
        hovered: (RetainedEntry) -> Boolean,
    ) {
        val descendantsAllowHit =
            ancestorAllowsHit &&
                (respectClips.not() || (retained.node is ClipChildrenNode).not() || hovered(retained))
        for (index in (0 until retained.effectiveChildCount).reversed()) {
            val child = retained.effectiveChildAt(index)
            if (child.placed || includeUnplaced) {
                visitHover(child, descendantsAllowHit, respectClips, includeUnplaced, failures, hovered)
            }
        }
        val hover = retained.node as? PointerHoverNode
        if (hover != null && (retained.placed || includeUnplaced)) {
            if (failures == null) {
                hover.onPointerHover(ancestorAllowsHit && hovered(retained))
            } else {
                failures.capture { hover.onPointerHover(ancestorAllowsHit && hovered(retained)) }
            }
        }
    }

    private fun dispatchNode(
        retained: RetainedEntry,
        event: PointerEvent,
        ancestorAllowsHit: Boolean,
    ): InputResult {
        val descendantsAllowHit =
            ancestorAllowsHit &&
                ((retained.node is ClipChildrenNode).not() || retained.contains(event.position))
        if (descendantsAllowHit) {
            for (index in (0 until retained.effectiveChildCount).reversed()) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) {
                    val result = dispatchNode(child, event, ancestorAllowsHit = true)
                    if (result === InputResult.Consumed) {
                        return result
                    }
                }
            }
        }
        val input = retained.node as? PointerInputNode
        if (input != null && ancestorAllowsHit && retained.contains(event.position)) {
            val result = input.onPointerEvent(eventFor(retained, event), localPosition(retained, event))
            if (result === InputResult.Consumed && event is PointerEvent.Press) {
                if (capture == null && input is PointerCaptureNode) {
                    capture = Capture(retained, event.button)
                    input.onPointerCaptureAcquired(event.button)
                }
            }
            return result
        }
        return InputResult.Ignored
    }

    private fun localPosition(
        retained: RetainedEntry,
        event: PointerEvent,
    ): IntOffset = retained.localToTree.localPosition(event.position)

    private fun containsPlaced(
        retained: RetainedEntry,
        owner: RetainedEntry,
    ): Boolean {
        if (retained.placed.not()) return false
        if (retained === owner) return true
        for (index in 0 until retained.effectiveChildCount) {
            if (containsPlaced(retained.effectiveChildAt(index), owner)) return true
        }
        return false
    }

    private data class Capture(
        val owner: RetainedEntry,
        val button: PointerButton,
        val scroll: ScrollGesture? = null,
    ) {
        fun accepts(event: PointerEvent): Boolean =
            when (event) {
                is PointerEvent.Move -> true
                is PointerEvent.Drag -> event.button == button
                is PointerEvent.Release -> event.button == button
                is PointerEvent.Press, is PointerEvent.Scroll -> false
            }
    }

    private data class Momentum(
        val owner: RetainedEntry,
        val identity: Any,
        val motion: ScrollMomentum,
    )

    private companion object {
        /**
         * Logical-pixel tolerance for floating-point displacement at a scroll boundary.
         */
        const val SCROLL_EPSILON = 0.000001
    }
}

private fun eventFor(
    retained: RetainedEntry,
    event: PointerEvent,
): PointerEvent {
    if (event !is PointerEvent.Drag || retained.localToTree.scale == 1.0) return event
    return PointerEvent.Drag(
        position = event.position,
        button = event.button,
        deltaX = event.deltaX / retained.localToTree.scale,
        deltaY = event.deltaY / retained.localToTree.scale,
    )
}

private fun RetainedEntry.contains(position: IntOffset): Boolean = localToTree.contains(measuredSize, position)
