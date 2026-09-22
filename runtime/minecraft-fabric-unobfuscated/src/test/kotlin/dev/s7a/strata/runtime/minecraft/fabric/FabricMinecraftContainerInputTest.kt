package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks the stable container wire protocol independently of each target's platform mouse numbering.
 */
internal class FabricMinecraftContainerInputTest {
    @Test
    fun preservesPrimaryAndSecondaryContainerActionsAcrossInputBackends() {
        assertEquals(0, containerButton(InputConstants.MOUSE_BUTTON_LEFT))
        assertEquals(1, containerButton(InputConstants.MOUSE_BUTTON_RIGHT))
    }

    @Test
    fun retainsOtherNativeButtonsForPickItemAndCustomBindings() {
        for (button in listOf(InputConstants.MOUSE_BUTTON_MIDDLE, InputConstants.MOUSE_BUTTON_4, Int.MAX_VALUE)) {
            assertEquals(button, containerButton(button))
        }
    }

    private fun containerButton(button: Int): Int = FabricMinecraftInventoryBridge.containerClickButton(MouseButtonEvent(10.0, 20.0, MouseButtonInfo(button, 0)))
}
