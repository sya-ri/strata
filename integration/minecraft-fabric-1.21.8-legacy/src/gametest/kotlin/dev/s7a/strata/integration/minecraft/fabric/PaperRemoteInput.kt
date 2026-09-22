package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.screens.Screen

/**
 * Sends one confirmed BMP character through the legacy native callback on the client thread.
 * This fixture uses Japanese text and rejects surrogate code units rather than splitting supplementary input.
 */
internal fun typePaperTestCharacter(
    screen: Screen,
    codePoint: Int,
): Boolean {
    require(codePoint in 0..0xFFFF && codePoint !in 0xD800..0xDFFF)
    return screen.charTyped(codePoint.toChar(), 0)
}
