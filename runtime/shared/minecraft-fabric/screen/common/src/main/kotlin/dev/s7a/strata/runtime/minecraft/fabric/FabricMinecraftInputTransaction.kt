package dev.s7a.strata.runtime.minecraft.fabric

import java.util.function.BooleanSupplier

/**
 * Owns one non-reentrant inventory input transaction on its caller's client thread.
 * Completion runs only after successful host dispatch and retains the guard until it returns or throws.
 * Every exit drops the transaction and its offered hover reference, preserving the original failure.
 *
 * @param I native input metadata selected by the owning adapter
 * @param H adapter-owned hover binding
 */
internal abstract class FabricMinecraftInputTransaction<I : Any, H : Any> {
    /**
     * Native metadata borrowed until host dispatch and completion both finish.
     */
    @JvmField
    var input: I? = null

    /**
     * First eligible hover binding offered by the current host dispatch.
     */
    @JvmField
    var offeredHover: H? = null

    /**
     * Runs non-reentrant host dispatch and its successful completion, preserving the host result and releasing every borrowed reference on exit.
     */
    fun dispatchInput(
        current: I,
        operation: BooleanSupplier,
    ): Boolean {
        check(input == null) { "Minecraft inventory input transactions are non-reentrant." }
        input = current
        offeredHover = null
        var completed = false
        try {
            val result = operation.asBoolean
            completed = true
            return result
        } finally {
            try {
                if (completed) finishInput(current)
            } finally {
                input = null
                offeredHover = null
            }
        }
    }

    /**
     * Completes successful host dispatch unless the adapter closed during that dispatch.
     */
    abstract fun finishInput(current: I)
}
