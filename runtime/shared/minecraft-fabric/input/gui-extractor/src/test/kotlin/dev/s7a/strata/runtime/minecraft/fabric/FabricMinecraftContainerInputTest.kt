package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks container button mapping and input transaction cleanup without loading a client.
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

    @Test
    fun failedNativeCompletionDoesNotPoisonTheNextInputTransaction() {
        for (consumed in listOf(false, true)) {
            val failure = IllegalStateException("native completion")
            val bridge = bridgeWithFailingCompletion(failure)
            try {
                val actual = assertThrows(IllegalStateException::class.java) { bridge.withMouseRelease(mouse()) { consumed } }
                assertSame(failure, actual)
                assertTrue(bridge.withPointerMove { true })
            } finally {
                bridge.close()
            }
        }
    }

    @Test
    fun failedHostDispatchSkipsNativeCompletionAndAllowsTheNextTransaction() {
        val nativeFailure = IllegalArgumentException("native completion")
        val hostFailure = IllegalStateException("host dispatch")
        val bridge = bridgeWithFailingCompletion(nativeFailure)
        try {
            val actual = assertThrows(IllegalStateException::class.java) { bridge.withMouseRelease(mouse()) { throw hostFailure } }
            assertSame(hostFailure, actual)
            assertTrue(bridge.withPointerMove { true })
        } finally {
            bridge.close()
        }
    }

    /**
     * Creates only owner-thread bookkeeping and injects a completion fault before any client access.
     * The lookup and client constructor arguments are unused by these transaction-only operations.
     */
    private fun bridgeWithFailingCompletion(failure: Throwable): FabricMinecraftInventoryBridge {
        val type = FabricMinecraftInventoryBridge::class.java
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val bridge = constructor.newInstance(*arrayOfNulls<Any>(constructor.parameterCount)) as FabricMinecraftInventoryBridge
        type.getDeclaredField("quickCrafting").apply { isAccessible = true }.setBoolean(bridge, true)
        val slots =
            object : HashSet<Any>() {
                override fun isEmpty(): Boolean = throw failure
            }
        type.getDeclaredField("quickCraftSlots").apply { isAccessible = true }.set(bridge, slots)
        return bridge
    }

    private fun mouse(button: Int = InputConstants.MOUSE_BUTTON_LEFT): MouseButtonEvent = MouseButtonEvent(10.0, 20.0, MouseButtonInfo(button, 0))

    private fun containerButton(button: Int): Int = FabricMinecraftInventoryBridge.containerClickButton(mouse(button))
}
