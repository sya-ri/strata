package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.TextInputEvent
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

/**
 * Verifies the Minecraft-to-common input boundary without loading a client singleton.
 */
internal class FabricMinecraftInputMappingTest {
    @Test
    fun mapsEverySupportedNativeButton() {
        assertEquals(PointerButton.Primary, mapMinecraftButton(0))
        assertEquals(PointerButton.Secondary, mapMinecraftButton(1))
        assertEquals(PointerButton.Middle, mapMinecraftButton(2))
        assertEquals(PointerButton.Auxiliary(0), mapMinecraftButton(3))
        assertEquals(PointerButton.Auxiliary(1), mapMinecraftButton(4))
        assertEquals(PointerButton.Auxiliary(Int.MAX_VALUE - 3), mapMinecraftButton(Int.MAX_VALUE))
        assertNull(mapMinecraftButton(-1))
    }

    @Test
    fun floorsOnlyFiniteIntegerRangeCoordinates() {
        assertEquals(Int.MIN_VALUE, mapMinecraftCoordinate(Int.MIN_VALUE.toDouble()))
        assertEquals(Int.MAX_VALUE, mapMinecraftCoordinate(Int.MAX_VALUE.toDouble() + 0.75))
        assertEquals(-2, mapMinecraftCoordinate(-1.25))
        assertNull(mapMinecraftCoordinate(Double.NaN))
        assertNull(mapMinecraftCoordinate(Double.POSITIVE_INFINITY))
        assertNull(mapMinecraftCoordinate(Int.MAX_VALUE.toDouble() + 1.0))
        assertEquals(IntOffset(-2, 3), mapMinecraftPosition(-1.25, 3.75))
        assertNull(mapMinecraftPosition(Double.NaN, 3.75))
        assertNull(mapMinecraftPosition(-1.25, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun mapsNativeVerticalScrollIntoCommonDirection() {
        assertEquals(-2.5, mapMinecraftVerticalScroll(2.5))
        assertEquals(2.5, mapMinecraftVerticalScroll(-2.5))
        assertEquals(0.0, mapMinecraftVerticalScroll(-0.0))
        assertEquals(2.5 to -3.5, mapMinecraftScroll(2.5, 3.5))
        assertEquals(null, mapMinecraftScroll(Double.NaN, 1.0))
        assertEquals(null, mapMinecraftScroll(1.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun mapsOnlyFiniteNativeDragDisplacementWithoutChangingDirection() {
        assertEquals(2.5 to -3.5, mapMinecraftDrag(2.5, -3.5))
        assertEquals(0.0 to -0.0, mapMinecraftDrag(0.0, -0.0))
        assertEquals(null, mapMinecraftDrag(Double.NaN, 1.0))
        assertEquals(null, mapMinecraftDrag(1.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun mapsKeyPressReleaseAndEveryModifierBit() {
        val flags =
            GLFW.GLFW_MOD_SHIFT or
                GLFW.GLFW_MOD_CONTROL or
                GLFW.GLFW_MOD_ALT or
                GLFW.GLFW_MOD_SUPER or
                GLFW.GLFW_MOD_CAPS_LOCK or
                GLFW.GLFW_MOD_NUM_LOCK
        val native = KeyEvent(GLFW.GLFW_KEY_ENTER, 17, flags)
        val modifiers = KeyboardModifiers(true, true, true, true, true, true)
        assertEquals(KeyboardEvent.Press(KeyCode.Enter, 17, modifiers), mapMinecraftKeyPress(native))
        assertEquals(KeyboardEvent.Release(KeyCode.Enter, 17, modifiers), mapMinecraftKeyRelease(native))
        assertEquals(KeyCode.Unknown, mapMinecraftKeyPress(KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 0, 0))?.key)
        assertNull(mapMinecraftKeyPress(KeyEvent(GLFW.GLFW_KEY_UNKNOWN - 1, 0, 0)))
    }

    @Test
    fun mapsCommittedUnicodeAndDetachedPreeditSnapshots() {
        assertEquals(TextInputEvent.Character(0x1F642), mapMinecraftCharacter(CharacterEvent(0x1F642)))
        assertNull(mapMinecraftCharacter(CharacterEvent(0xD800)))
        val blocks = arrayListOf("first", "second")
        val mapped = mapMinecraftPreedit(PreeditEvent("composition", 4, blocks, 1))
        blocks.clear()
        assertEquals(TextInputEvent.Preedit("composition", 4, listOf("first", "second"), 1), mapped)
    }
}
