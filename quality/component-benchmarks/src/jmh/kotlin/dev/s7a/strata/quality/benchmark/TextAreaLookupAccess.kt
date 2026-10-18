package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.MinecraftTextLine
import java.util.function.IntUnaryOperator

/**
 * Binds one actual retained line outside sampling; the timed argument and result remain primitive ints.
 */
internal object TextAreaLookupAccess {
    /**
     * Borrows this immutable line for the caller's bounded benchmark-state lifetime.
     */
    fun create(line: Any): IntUnaryOperator {
        val retained = line as MinecraftTextLine
        return IntUnaryOperator { retained.offsetAt(it) }
    }
}
