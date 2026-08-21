package dev.s7a.strata.input

import dev.s7a.strata.geometry.IntOffset

/**
 * Initial pointer protocol events delivered after layout hit testing.
 *
 * Event-specific data is part of each variant, so invalid action combinations cannot be represented.
 */
public sealed interface PointerEvent {
    /**
     * The event position in tree coordinates.
     */
    public val position: IntOffset

    /**
     * A pointer press with a typed button.
     *
     * @property position the tree-coordinate event position.
     * @property button the logical button that was pressed.
     */
    public data class Press(
        override val position: IntOffset,
        public val button: PointerButton,
    ) : PointerEvent

    /**
     * A pointer release with a typed button.
     *
     * @property position the tree-coordinate event position.
     * @property button the logical button that was released.
     */
    public data class Release(
        override val position: IntOffset,
        public val button: PointerButton,
    ) : PointerEvent

    /**
     * A pointer movement event.
     *
     * @property position the tree-coordinate event position.
     */
    public data class Move(
        override val position: IntOffset,
    ) : PointerEvent

    /**
     * A pointer movement event while one typed button remains held.
     *
     * Positive [deltaX] requests motion toward increasing logical x.
     * Positive [deltaY] requests motion toward increasing logical y.
     * Adapters normalize native coordinates and units into this logical displacement.
     *
     * @property position current tree-coordinate event position.
     * @property button logical button held for the drag.
     * @property deltaX horizontal logical displacement since the previous native drag event.
     * @property deltaY vertical logical displacement since the previous native drag event.
     */
    public data class Drag(
        override val position: IntOffset,
        public val button: PointerButton,
        public val deltaX: Double,
        public val deltaY: Double,
    ) : PointerEvent {
        init {
            require(deltaX.isFinite()) { "Horizontal drag displacement must be finite." }
            require(deltaY.isFinite()) { "Vertical drag displacement must be finite." }
        }
    }

    /**
     * A pointer scroll event with finite logical input displacement.
     *
     * Positive [deltaX] requests motion toward increasing logical x.
     * Positive [deltaY] requests motion toward increasing logical y.
     * Adapters normalize native signs and units into this logical displacement.
     *
     * @property position the tree-coordinate event position.
     * @property deltaX horizontal logical displacement.
     * @property deltaY vertical logical displacement.
     */
    public data class Scroll(
        override val position: IntOffset,
        public val deltaX: Double,
        public val deltaY: Double,
    ) : PointerEvent {
        init {
            require(deltaX.isFinite()) { "Horizontal scroll displacement must be finite." }
            require(deltaY.isFinite()) { "Vertical scroll displacement must be finite." }
        }
    }
}
