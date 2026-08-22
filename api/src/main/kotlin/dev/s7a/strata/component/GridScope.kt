package dev.s7a.strata.component

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.GridAlignmentParentData
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct grid children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the grid callback runs.
 */
@StrataDsl
public sealed class GridScope private constructor() : UiScope() {
    /**
     * Appends a two-axis cell-placement override to this modifier chain.
     *
     * The parent grid consumes the innermost alignment provider on the direct child chain.
     *
     * @param alignment placement of the direct child inside its measured cell.
     * @return this chain with grid alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: Alignment): Modifier {
        checkUsable()
        return then(GridAlignmentParentData.Element(GridAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime grid scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the grid builder.
         *
         * @return a fresh callback-lifetime grid scope.
         */
        @JvmSynthetic
        internal fun create(): GridScope = ScopeImpl()
    }

    private class ScopeImpl : GridScope()
}
