package dev.s7a.strata.component

import dev.s7a.strata.layout.RowAlignmentParentData
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.layout.WeightParentData
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct row children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the row callback runs.
 */
@StrataDsl
public sealed class RowScope private constructor() : UiScope() {
    /**
     * Appends weighted parent-data behavior to this modifier chain.
     *
     * The parent row consumes the innermost weight provider on the direct child chain.
     *
     * @param weight the positive finite allocation weight.
     * @param fill whether the child receives the complete allocated main-axis slot.
     * @return this chain with weighted parent-data behavior appended.
     * @throws IllegalArgumentException when [weight] is non-positive or non-finite.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.weight(
        weight: Float,
        fill: Boolean = true,
    ): Modifier {
        checkUsable()
        require(weight.isFinite() && 0 < weight) { "Weight must be positive and finite." }
        return then(WeightParentData.Element(WeightParentData.Data(weight, fill)))
    }

    /**
     * Appends a vertical cross-axis placement override to this modifier chain.
     *
     * @param alignment the direct child placement policy.
     * @return this chain with row alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: VerticalAlignment): Modifier {
        checkUsable()
        return then(RowAlignmentParentData.Element(RowAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime row scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the row builder.
         *
         * @return a fresh callback-lifetime row scope.
         */
        @JvmSynthetic
        internal fun create(): RowScope = ScopeImpl()
    }

    private class ScopeImpl : RowScope()
}
