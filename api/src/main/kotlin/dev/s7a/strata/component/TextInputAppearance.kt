package dev.s7a.strata.component

import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.render.ArgbColor

/**
 * Immutable frame and editing-decoration appearance shared by single-line and multiline editors.
 *
 * Appearance does not change the four-pixel editor padding, text metrics, state ownership, or focus.
 * Glyph color and shadow remain controlled by the component's textStyle and font arguments.
 * Keep custom images and this value outside reevaluated content; an appearance-only update repaints the retained editor.
 */
public sealed interface TextInputAppearance {
    /**
     * Uses the active profile's field sprites and legacy white caret and composition underline.
     */
    public data object Default : TextInputAppearance

    /**
     * Supplies three independently resolved nine-slice frames and visible editing decorations.
     *
     * Images remain caller-owned and resource identifiers resolve through the host's pinned resource context.
     * Every frame must have a nonempty center after the border is removed; invalid images fail during evaluation.
     * Transparent frame pixels reveal the caller's background; the default profile frame is not drawn underneath.
     *
     * @property normal unfocused enabled frame.
     * @property focused focused enabled frame.
     * @property caretColor insertion caret color, including the single-line append caret.
     * @property disabled frame used whenever editing is disabled.
     * @property border source border widths; destination borders clamp to half of each destination axis.
     * @property compositionUnderlineColor focused IME block underline color.
     */
    public data class Custom(
        public val normal: ImageSource,
        public val focused: ImageSource,
        public val caretColor: ArgbColor,
        public val disabled: ImageSource = normal,
        public val border: Insets = Insets.all(1),
        public val compositionUnderlineColor: ArgbColor = caretColor,
    ) : TextInputAppearance
}
