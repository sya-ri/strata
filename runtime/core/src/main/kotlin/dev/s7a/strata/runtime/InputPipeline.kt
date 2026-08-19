package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
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
    ): InputResult = dispatchNode(root, event)

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
