package dev.s7a.strata.component

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.StackAlignmentParentData
import dev.s7a.strata.modifier.Modifier
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for emitting and configuring direct stack children.
 *
 * Instances are created only by the library and are valid on their constructing thread while the stack callback runs.
 */
@StrataDsl
public sealed class StackScope private constructor() : UiScope() {
    /**
     * Appends a two-axis placement override to this modifier chain.
     *
     * The parent stack consumes the innermost alignment provider on the direct child chain.
     *
     * @param alignment the direct child placement policy.
     * @return this chain with stack alignment behavior appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.align(alignment: Alignment): Modifier {
        checkUsable()
        return then(StackAlignmentParentData.Element(StackAlignmentParentData.Data(alignment)))
    }

    /**
     * Internal construction boundary for one callback-lifetime stack scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the stack builder.
         *
         * @return a fresh callback-lifetime stack scope.
         */
        @JvmSynthetic
        internal fun create(): StackScope = ScopeImpl()
    }

    private class ScopeImpl : StackScope()
}
