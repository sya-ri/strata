package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.render.ArgbColor

/**
 * Fixed, immutable presentation colors shared by the compiled README examples on any thread.
 */
internal object ReadmeDemoColors {
    /**
     * Opaque row surface whose natural bounds make automatic layout visible.
     */
    val row: ArgbColor = ArgbColor(0xFF4A4A4A.toInt())
}
