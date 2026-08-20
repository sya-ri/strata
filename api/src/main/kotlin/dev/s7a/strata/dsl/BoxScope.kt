package dev.s7a.strata.dsl

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.BoxAlignmentParentData
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct box children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the box callback runs.
 */
@StrataDsl
public sealed class BoxScope private constructor() : UiScope() {
    /**
     * Appends a two-axis placement override to this modifier chain.
     *
     * The parent box consumes the innermost alignment provider on the direct child chain.
     *
     * @param alignment the direct child placement policy.
     * @return this chain with box alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: Alignment): Modifier {
        checkUsable()
        return then(BoxAlignmentParentData.Element(BoxAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime box scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the box builder.
         *
         * @return a fresh callback-lifetime box scope.
         */
        @JvmSynthetic
        internal fun create(): BoxScope = ScopeImpl()
    }

    private class ScopeImpl : BoxScope()
}
