package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.PointerButton

/**
 * Converts one native mouse button number to the typed common pointer button.
 *
 * @param button native GLFW-style button number.
 * @return the typed button, or `null` for an unsupported negative button.
 */
@JvmSynthetic
internal fun mapMinecraftButton(button: Int): PointerButton? =
    when (button) {
        0 -> PointerButton.Primary
        1 -> PointerButton.Secondary
        2 -> PointerButton.Middle
        else -> if (2 < button) PointerButton.Auxiliary(button - 3) else null
    }
