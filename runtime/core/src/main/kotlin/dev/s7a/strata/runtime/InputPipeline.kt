package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode

/**
 * Dispatches pointer events through laid-out retained nodes and owns one captured gesture.
 *
 * The enclosing tree serializes every operation on its owner thread.
 * Capture holds only its retained entry and starting button until release, cancellation, or terminal cleanup.
 * Callback failures propagate unchanged after the capture reference has been cleared when appropriate.
 */
internal class InputPipeline(
    private val focusedInputPipeline: FocusedInputPipeline,
) {
    private var capture: Capture? = null

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
        if (event is PointerEvent.Press && event.button === PointerButton.Primary) {
            focusedInputPipeline.acquireFromPointer(root, event.position)
        }
        val captured = capture
        if (captured != null && captured.accepts(event)) {
            if (event is PointerEvent.Release) capture = null
            val input = captured.owner.node as PointerCaptureNode
            input.onPointerEvent(event, localPosition(captured.owner, event))
            return InputResult.Consumed
        }
        return dispatchNode(root.effectiveRoot, event, ancestorAllowsHit = true)
    }

    /**
     * Cancels capture when its owner no longer participates in committed layout.
     *
     * @param root current logical root after all placement has completed on the tree owner thread.
     * @throws Throwable when the cancelled owner rejects its notification; capture is already cleared.
     */
    fun layoutCommitted(root: RetainedNode) {
        val captured = capture ?: return
        if (containsPlaced(root.effectiveRoot, captured.owner).not()) cancelCapture()
    }

    /**
     * Cancels capture before one retained component or modifier starts its lifecycle cleanup.
     *
     * @param entry owner-thread entry whose callbacks and resources have not yet been disposed.
     * @throws Throwable when cancellation fails; the caller must continue remaining lifecycle cleanup.
     */
    fun entryWillCleanup(entry: RetainedEntry) {
        if (capture?.owner === entry) cancelCapture()
    }

    /**
     * Clears the retained capture reference before notifying its previous owner once.
     *
     * This owner-thread operation is a no-op without capture and does not dispose or otherwise retain the node.
     *
     * @throws Throwable when the captured owner's cancellation callback fails.
     */
    fun cancelCapture() {
        val captured = capture ?: return
        capture = null
        (captured.owner.node as PointerCaptureNode).onPointerCaptureCancelled(captured.button)
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
        visitHover(root, ancestorAllowsHit = true) { retained -> position in retained.bounds }
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
                ((retained.node is ClipChildrenNode).not() || event.position in retained.bounds)
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
        if (input != null && ancestorAllowsHit && event.position in retained.bounds) {
            val result = input.onPointerEvent(event, localPosition(retained, event))
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
    ): IntOffset =
        IntOffset(
            Math.subtractExact(event.position.x, retained.bounds.left),
            Math.subtractExact(event.position.y, retained.bounds.top),
        )

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
    ) {
        fun accepts(event: PointerEvent): Boolean =
            when (event) {
                is PointerEvent.Move -> true
                is PointerEvent.Drag -> event.button == button
                is PointerEvent.Release -> event.button == button
                is PointerEvent.Press, is PointerEvent.Scroll -> false
            }
    }
}
