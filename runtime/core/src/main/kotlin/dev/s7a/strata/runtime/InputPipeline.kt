package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode

/**
 * Dispatches pointer events through laid-out retained nodes.
 */
internal class InputPipeline {
    /**
     * Dispatches [event] deepest and topmost first.
     *
     * @param root the laid-out retained root.
     * @param event the tree-coordinate event.
     * @return consumed when a node handles the event, otherwise ignored.
     */
    fun dispatch(
        root: RetainedEntry,
        event: PointerEvent,
    ): InputResult {
        if (event is PointerEvent.Move) {
            updateHover(root, event)
        }
        return dispatchNode(root, event)
    }

    /**
     * Clears hover from every placed capable node before a retained session detaches.
     *
     * @param root the laid-out retained root.
     * @throws Throwable when a hover callback rejects the exit transition.
     */
    fun clearHover(root: RetainedEntry) {
        visitHover(root) { _ -> false }
    }

    private fun updateHover(
        root: RetainedEntry,
        event: PointerEvent.Move,
    ) {
        visitHover(root) { retained -> event.position in retained.bounds }
    }

    private fun visitHover(
        retained: RetainedEntry,
        hovered: (RetainedEntry) -> Boolean,
    ) {
        for (index in (0 until retained.effectiveChildCount).reversed()) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                visitHover(child, hovered)
            }
        }
        val hover = retained.node as? PointerHoverNode
        if (hover != null && retained.placed) {
            hover.onPointerHover(hovered(retained))
        }
    }

    private fun dispatchNode(
        retained: RetainedEntry,
        event: PointerEvent,
    ): InputResult {
        for (index in (0 until retained.effectiveChildCount).reversed()) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                val result = dispatchNode(child, event)
                if (result === InputResult.Consumed) {
                    return result
                }
            }
        }
        val input = retained.node as? PointerInputNode
        if (input != null && event.position in retained.bounds) {
            val local =
                IntOffset(
                    Math.subtractExact(event.position.x, retained.bounds.left),
                    Math.subtractExact(event.position.y, retained.bounds.top),
                )
            return input.onPointerEvent(event, local)
        }
        return InputResult.Ignored
    }
}
