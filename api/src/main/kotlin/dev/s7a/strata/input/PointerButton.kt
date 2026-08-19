package dev.s7a.strata.input

/**
 * Typed pointer button values accepted by the initial pointer protocol.
 */
public sealed interface PointerButton {
    /**
     * The primary button.
     */
    public data object Primary : PointerButton

    /**
     * The secondary button.
     */
    public data object Secondary : PointerButton

    /**
     * The middle button.
     */
    public data object Middle : PointerButton

    /**
     * An auxiliary button with a non-negative logical index.
     *
     * @property index the logical auxiliary-button index.
     */
    public data class Auxiliary(
        public val index: Int,
    ) : PointerButton {
        init {
            require(0 <= index) { "Auxiliary button indices must be non-negative." }
        }
    }
}
