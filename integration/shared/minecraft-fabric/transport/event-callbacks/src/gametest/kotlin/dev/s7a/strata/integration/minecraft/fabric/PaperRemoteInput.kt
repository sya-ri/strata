package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent

/**
 * Sends one confirmed code point through the native record callback on the client thread.
 */
internal fun typePaperTestCharacter(
    screen: Screen,
    codePoint: Int,
): Boolean = screen.charTyped(CharacterEvent(codePoint, 0))
