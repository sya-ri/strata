package dev.s7a.strata.modifier

import dev.s7a.strata.element.Element

/**
 * An active modifier owning one independent, display-only retained root.
 * The content is validated with the containing description and shares its session and lifecycle.
 * It is not a logical child and does not change the modifier's single virtual child.
 */
public interface RootOverlayModifierElement : ModifierElement {
    /**
     * Immutable overlay description, retained even while its presentation is hidden.
     */
    public val content: Element
}
