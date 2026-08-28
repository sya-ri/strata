package dev.s7a.strata.component

import dev.s7a.strata.layout.FlowRowAlignmentParentData
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct flow-row children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the flow-row callback runs.
 */
@StrataDsl
public sealed class FlowRowScope private constructor() : UiScope() {
    /**
     * Appends a vertical placement override within the child's measured row.
     *
     * The parent flow row consumes the innermost alignment provider on the direct child chain.
     * This override affects placement only and does not stretch the child or align the complete group of rows.
     *
     * @param alignment placement of the direct child within the height of its row.
     * @return this chain with flow-row alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: VerticalAlignment): Modifier {
        checkUsable()
        return then(FlowRowAlignmentParentData.Element(FlowRowAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime flow-row scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the flow-row builder.
         *
         * @return a fresh callback-lifetime flow-row scope.
         */
        @JvmSynthetic
        internal fun create(): FlowRowScope = ScopeImpl()
    }

    private class ScopeImpl : FlowRowScope()
}
