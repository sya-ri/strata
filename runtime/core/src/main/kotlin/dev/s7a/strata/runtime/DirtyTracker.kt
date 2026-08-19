package dev.s7a.strata.runtime

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase

/**
 * Expands downstream local phases and propagates measurement work to ancestors.
 */
internal class DirtyTracker {
    /**
     * Records [mask] on [retained] and conservatively expands downstream work.
     *
     * @param retained the retained node whose phases changed.
     * @param mask the phases reported by an element or node-local change.
     */
    fun record(
        retained: RetainedNode,
        mask: DirtyMask,
    ) {
        val expanded = expand(mask)
        retained.dirty += expanded
        if (DirtyPhase.Measure in mask) {
            val ancestorMask = DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Layout, DirtyPhase.Paint, DirtyPhase.Semantics)
            var ancestor = retained.parent
            while (ancestor != null) {
                ancestor.dirty += ancestorMask
                ancestor = ancestor.parent
            }
        }
    }

    /**
     * Marks structural work on [retained] and all ancestors.
     *
     * @param retained the parent whose direct-child structure changed.
     */
    fun structural(retained: RetainedNode) {
        var current: RetainedNode? = retained
        while (current != null) {
            current.dirty += DirtyMask.All
            current = current.parent
        }
    }

    private fun expand(mask: DirtyMask): DirtyMask {
        var expanded = mask
        if (DirtyPhase.Measure in mask) {
            expanded += DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint, DirtyPhase.Semantics)
        } else if (DirtyPhase.Layout in mask) {
            expanded += DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)
        }
        return expanded
    }
}
