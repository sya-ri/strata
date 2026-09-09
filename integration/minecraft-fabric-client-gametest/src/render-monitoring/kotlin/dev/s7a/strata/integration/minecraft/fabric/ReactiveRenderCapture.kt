package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Names independent native/reference evidence and its opaque comparison bounds.
 */
internal enum class ReactiveRenderCapture(
    val artifactName: String,
    val size: IntSize,
) {
    /**
     * State and overlay composition evidence.
     */
    State("reactive-rendering", IntSize(160, 48)),

    /**
     * Standard input appearance evidence.
     */
    InputAppearance("input-appearance", IntSize(160, 64)),
}
