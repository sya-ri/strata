package dev.s7a.strata.component

/**
 * Visual indicator rendered by a selected [Tab].
 *
 * Standard indicators encode reusable state presentation rather than one screen's domain state.
 */
public sealed interface TabSelectionIndicator {
    /**
     * Paints the active profile's ordinary one-pixel underline below the label.
     */
    public data object Underline : TabSelectionIndicator

    /**
     * Emits one caller-defined indicator root below the label when the tab is selected.
     *
     * @property content callback that must emit exactly one indicator root.
     */
    public class Custom(
        public val content: UiScope.() -> Unit,
    ) : TabSelectionIndicator
}
