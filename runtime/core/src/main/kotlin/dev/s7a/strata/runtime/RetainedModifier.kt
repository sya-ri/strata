package dev.s7a.strata.runtime

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.node.ModifierNode

/**
 * One retained active modifier entry owned by a logical component.
 *
 * Modifier entries are reconciled positionally and never replace their component owner.
 * Their effective child link is installed separately from logical component children.
 *
 * @property modifierNode the typed active modifier behavior owned by this entry.
 */
internal class RetainedModifier(
    var element: ModifierElement,
    val modifierNode: ModifierNode,
) : RetainedEntry(modifierNode) {
    override val effectiveChildCount: Int
        get() = 1

    /**
     * The next effective entry, installed by modifier reconciliation.
     */
    var virtualChild: RetainedEntry? = null

    override fun effectiveChildAt(index: Int): RetainedEntry {
        check(index == 0) { "A modifier exposes exactly one effective child." }
        return checkNotNull(virtualChild) { "Modifier virtual child is not linked." }
    }
}
