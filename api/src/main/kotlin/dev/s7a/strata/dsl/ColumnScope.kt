package dev.s7a.strata.dsl

import dev.s7a.strata.layout.ColumnAlignmentParentData
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.WeightParentData
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct column children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the column callback runs.
 */
@StrataDsl
public sealed class ColumnScope private constructor() : UiScope() {
    /**
     * Appends weighted parent-data behavior to this modifier chain.
     *
     * The parent column consumes the innermost weight provider on the direct child chain.
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
     * Appends a horizontal cross-axis placement override to this modifier chain.
     *
     * @param alignment the direct child placement policy.
     * @return this chain with column alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: HorizontalAlignment): Modifier {
        checkUsable()
        return then(ColumnAlignmentParentData.Element(ColumnAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime column scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the column builder.
         *
         * @return a fresh callback-lifetime column scope.
         */
        @JvmSynthetic
        internal fun create(): ColumnScope = ScopeImpl()
    }

    private class ScopeImpl : ColumnScope()
}
