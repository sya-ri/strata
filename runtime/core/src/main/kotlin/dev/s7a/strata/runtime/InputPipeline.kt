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
 * A private capability index is keyed by logical root identity and the latest completed layout revision.
 * It retains current pointer/hover entries and required clip ancestry only, bounded by the placed effective tree.
 * Every layout commit and entry cleanup drops the index before callbacks; terminal capture cancellation also clears it.
 * Paint/source invalidation cannot mutate committed membership or geometry before the next layout boundary.
 * Index eviction and capture notification stay with this single tree-owned pointer lifetime.
 */
@Suppress("TooManyFunctions")
internal class InputPipeline(
    private val focusedInputPipeline: FocusedInputPipeline,
) {
    private var capture: Capture? = null
    private var layoutRevision = 0L
    private var indexedRevision = 0L
    private var indexedRoot: RetainedNode? = null
    private var pointerEntries: List<PointerEntry> = emptyList()

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
            is PointerEvent.Move -> {
                prepare(root)
                updateHover(event.position)
            }

            is PointerEvent.Drag -> {
                prepare(root)
                updateHover(event.position)
            }

            is PointerEvent.Press,
            is PointerEvent.Release,
            is PointerEvent.Scroll,
            -> {
                Unit
            }
        }
        if (event is PointerEvent.Press && event.button === PointerButton.Primary) {
            focusedInputPipeline.acquireFromPointer(root, event.position)
        }
        val captured = capture
        if (captured != null && captured.accepts(event)) {
            if (event is PointerEvent.Release) capture = null
            val input = captured.owner.node as PointerCaptureNode
            input.onPointerEvent(eventFor(captured.owner, event), localPosition(captured.owner, event))
            return InputResult.Consumed
        }
        prepare(root)
        for (entry in pointerEntries) {
            val result = dispatchEntry(entry, event, ancestorAllowsHit = true)
            if (result === InputResult.Consumed) return result
        }
        return InputResult.Ignored
    }

    /**
     * Cancels capture when its owner no longer participates in committed layout.
     *
     * @param root current logical root after all placement has completed on the tree owner thread.
     * @throws Throwable when the cancelled owner rejects its notification; capture is already cleared.
     */
    fun layoutCommitted(root: RetainedNode) {
        clearIndex()
        layoutRevision += 1L
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
        clearIndex()
        if (capture?.owner === entry) cancelCapture()
    }

    /**
     * Clears the retained capture reference before notifying its previous owner once.
     *
     * This owner-thread operation evicts derived pointer membership even without capture and does not dispose a node.
     * Terminal tree release uses this same path, so every indexed root and entry is dropped before cancellation callbacks.
     *
     * @throws Throwable when the captured owner's cancellation callback fails.
     */
    fun cancelCapture() {
        clearIndex()
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

    private fun updateHover(position: IntOffset) {
        for (entry in pointerEntries) visitEntryHover(entry, position, ancestorAllowsHit = true)
    }

    private fun visitEntryHover(
        entry: PointerEntry,
        position: IntOffset,
        ancestorAllowsHit: Boolean,
    ) {
        val retained = entry.owner
        val descendantsAllowHit = ancestorAllowsHit && ((retained.node is ClipChildrenNode).not() || retained.contains(position))
        for (child in entry.children) visitEntryHover(child, position, descendantsAllowHit)
        (retained.node as? PointerHoverNode)?.onPointerHover(ancestorAllowsHit && retained.contains(position))
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

    private fun dispatchEntry(
        entry: PointerEntry,
        event: PointerEvent,
        ancestorAllowsHit: Boolean,
    ): InputResult {
        val retained = entry.owner
        val descendantsAllowHit =
            ancestorAllowsHit &&
                ((retained.node is ClipChildrenNode).not() || retained.contains(event.position))
        if (descendantsAllowHit) {
            for (child in entry.children) {
                val result = dispatchEntry(child, event, ancestorAllowsHit = true)
                if (result === InputResult.Consumed) return result
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
        var current: RetainedEntry? = owner
        while (current != null) {
            if (current.placed.not()) return false
            if (current === retained) return true
            current = current.parent
        }
        return false
    }

    private fun prepare(root: RetainedNode) {
        if (indexedRoot === root && indexedRevision == layoutRevision) return
        clearIndex()
        val entries = ArrayList<PointerEntry>()
        collect(root.effectiveRoot, entries)
        pointerEntries = entries
        indexedRoot = root
        indexedRevision = layoutRevision
    }

    private fun collect(
        retained: RetainedEntry,
        entries: MutableList<PointerEntry>,
    ) {
        if (retained.placed.not()) return
        val start = entries.size
        for (index in (0 until retained.effectiveChildCount).reversed()) collect(retained.effectiveChildAt(index), entries)
        val capable = retained.node is PointerInputNode || retained.node is PointerHoverNode
        if (capable || retained.node is ClipChildrenNode && start < entries.size) {
            val descendants = entries.subList(start, entries.size)
            val children = if (descendants.isEmpty()) emptyList() else ArrayList(descendants)
            descendants.clear()
            entries.add(PointerEntry(retained, children))
        }
    }

    private fun clearIndex() {
        indexedRoot = null
        pointerEntries = emptyList()
    }

    /**
     * One current capable entry or relevant ancestor clip; inert unclipped ancestry is flattened.
     * Children preserve reverse sibling/deepest callback order and contain only the current committed placed tree.
     * This private immutable descriptor retains no copied geometry or authoritative source state.
     */
    private class PointerEntry(
        val owner: RetainedEntry,
        val children: List<PointerEntry>,
    )

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
