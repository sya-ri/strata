package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable TrueType provider settings passed to a CPU backend.
 * Signed sizes and oversampling, including either signed zero, preserve the selected native provider's numeric behavior.
 * A backend may reject a native operation that cannot be executed safely, but these values do not make a font document malformed.
 *
 * @property size logical provider font size.
 * @property oversample source-pixel oversampling factor.
 * @property shiftX configured horizontal shift.
 * @property shiftY configured vertical shift.
 * @throws IllegalArgumentException when any setting is non-finite.
 */
public data class MinecraftTrueTypeSettings(
    public val size: Float = 11.0f,
    public val oversample: Float = 1.0f,
    public val shiftX: Float = 0.0f,
    public val shiftY: Float = 0.0f,
) {
    init {
        require(size.isFinite()) { "TrueType size must be finite." }
        require(oversample.isFinite()) { "TrueType oversampling must be finite." }
        require(shiftX.isFinite() && shiftY.isFinite()) { "TrueType shifts must be finite." }
    }
}
