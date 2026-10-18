package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

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
    fun decodesQuickCraftModesBeforeEncodingTheNativeProtocol() {
        for (creativePick in listOf(false, true)) {
            assertSame(FabricMinecraftQuickCraftMode.DISTRIBUTE, FabricMinecraftQuickCraftMode.fromButton(0, creativePick))
            assertSame(FabricMinecraftQuickCraftMode.ONE_PER_SLOT, FabricMinecraftQuickCraftMode.fromButton(1, creativePick))
        }
        for (button in listOf(2, 3, 4, Int.MAX_VALUE)) {
            assertNull(FabricMinecraftQuickCraftMode.fromButton(button, false))
            assertSame(FabricMinecraftQuickCraftMode.FILL, FabricMinecraftQuickCraftMode.fromButton(button, true))
        }
        assertEquals(0, FabricMinecraftQuickCraftMode.DISTRIBUTE.nativeCode())
        assertEquals(1, FabricMinecraftQuickCraftMode.ONE_PER_SLOT.nativeCode())
        assertEquals(2, FabricMinecraftQuickCraftMode.FILL.nativeCode())
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

    @Test
    fun completionKeepsTheReentrancyGuardAndThenDropsBorrowedReferences() {
        val event = Any()
        val hover = Any()
        val failure = IllegalStateException("native completion")
        val transaction =
            object : FabricMinecraftInputTransaction<Any, Any>() {
                override fun finishInput(current: Any) {
                    assertSame(event, current)
                    assertSame(event, input)
                    assertSame(hover, offeredHover)
                    assertThrows(IllegalStateException::class.java) { dispatchInput(Any()) { false } }
                    throw failure
                }
            }
        val actual =
            assertThrows(IllegalStateException::class.java) {
                transaction.dispatchInput(event) {
                    transaction.offeredHover = hover
                    true
                }
            }
        assertSame(failure, actual)
        assertNull(transaction.input)
        assertNull(transaction.offeredHover)
    }

    /**
     * Creates only owner-thread bookkeeping and injects a completion fault before any client access.
     * The lookup and client constructor arguments are unused by these transaction-only operations.
     */
    private fun bridgeWithFailingCompletion(failure: Throwable): FabricMinecraftInventoryBridge {
        val type = FabricMinecraftInventoryBridge::class.java
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val bridge = constructor.newInstance(*arrayOfNulls<Any>(constructor.parameterCount)) as FabricMinecraftInventoryBridge
        fixtureField(type, "quickCrafting").setBoolean(bridge, true)
        val slots =
            object : HashMap<Any, Any>() {
                override fun isEmpty(): Boolean = throw failure
            }
        fixtureField(type, "quickCraftSlots").set(bridge, slots)
        return bridge
    }

    /**
     * Finds fixture-owned fault injection points after input ownership moves into the common controller.
     */
    private fun fixtureField(
        type: Class<*>,
        name: String,
    ): Field =
        generateSequence(type) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .first { candidate -> candidate.name == name }
            .apply { isAccessible = true }

    private fun mouse(button: Int = InputConstants.MOUSE_BUTTON_LEFT): MouseButtonEvent = MouseButtonEvent(10.0, 20.0, MouseButtonInfo(button, 0))

    private fun containerButton(button: Int): Int = FabricMinecraftInventoryBridge.containerClickButton(mouse(button))
}
