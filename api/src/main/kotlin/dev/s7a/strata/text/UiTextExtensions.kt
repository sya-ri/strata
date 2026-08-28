package dev.s7a.strata.text

import dev.s7a.strata.resource.ResourceId

/**
 * Retains this text with a resource-pack font identifier without loading resources or changing its content.
 *
 * This synchronous operation returns an immutable wrapper and preserves the receiver's ownership and thread-safety contract.
 * Resource lookup and missing-font behavior belong to the runtime that later resolves the wrapper.
 * An explicit font already nested within this text takes precedence over the inherited [font].
 *
 * @receiver unresolved text to render with the selected font.
 * @param font structural resource identifier of the font definition.
 * @return a new unresolved text value carrying [font].
 */
public fun UiText.withFont(font: ResourceId): UiText = UiText.WithFont(this, font)
