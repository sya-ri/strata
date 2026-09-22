package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.input.PointerButton

/**
 * Converts SDL mouse numbering at the native boundary without exposing it to retained input.
 */
@JvmSynthetic
internal fun mapMinecraftButton(button: Int): PointerButton? =
    when (button) {
        InputConstants.MOUSE_BUTTON_LEFT -> PointerButton.Primary
        InputConstants.MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
        InputConstants.MOUSE_BUTTON_MIDDLE -> PointerButton.Middle
        else -> if (InputConstants.MOUSE_BUTTON_4 <= button) PointerButton.Auxiliary(button - InputConstants.MOUSE_BUTTON_4) else null
    }
