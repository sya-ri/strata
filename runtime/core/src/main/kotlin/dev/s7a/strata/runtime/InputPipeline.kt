package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.ClipChildrenNode
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
        when (event) {
            is PointerEvent.Move -> updateHover(root, event.position)

            is PointerEvent.Drag -> updateHover(root, event.position)

            is PointerEvent.Press,
            is PointerEvent.Release,
            is PointerEvent.Scroll,
            -> Unit
        }
        return dispatchNode(root, event, ancestorAllowsHit = true)
    }

    /**
     * Clears hover from every placed capable node before a retained session detaches.
     *
     * @param root the laid-out retained root.
     * @throws Throwable when a hover callback rejects the exit transition.
     */
    fun clearHover(root: RetainedEntry) {
        visitHover(root, respectClips = false) { _ -> false }
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
        hovered: (RetainedEntry) -> Boolean,
    ) {
        val descendantsAllowHit =
            ancestorAllowsHit &&
                (respectClips.not() || (retained.node is ClipChildrenNode).not() || hovered(retained))
        for (index in (0 until retained.effectiveChildCount).reversed()) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                visitHover(child, descendantsAllowHit, respectClips, hovered)
            }
        }
        val hover = retained.node as? PointerHoverNode
        if (hover != null && retained.placed) {
            hover.onPointerHover(ancestorAllowsHit && hovered(retained))
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
                    val result = dispatchNode(child, event, ancestorAllowsHit = descendantsAllowHit)
                    if (result === InputResult.Consumed) {
                        return result
                    }
                }
            }
        }
        val input = retained.node as? PointerInputNode
        if (input != null && ancestorAllowsHit && event.position in retained.bounds) {
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
