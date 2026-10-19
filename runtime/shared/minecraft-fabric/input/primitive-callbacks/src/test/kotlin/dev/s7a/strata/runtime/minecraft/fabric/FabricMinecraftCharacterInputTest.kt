package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.TextInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies adjacent UTF-16 callback assembly and reset in the actual primitive screen-input family.
 */
internal class FabricMinecraftCharacterInputTest {
    @Test
    fun legacyCharacterCallbacksAssembleOnlyAdjacentSurrogatePairs() {
        val input = FabricMinecraftCharacterInput()
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
        assertNull(input.accept('\uDE42'))
        assertEquals(TextInputEvent.Character('本'.code), input.accept('本'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character('A'.code), input.accept('A'))
        assertNull(input.accept('\uDE42'))
        assertNull(input.accept('\uD83C'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
    }

    @Test
    fun legacyCharacterResetDiscardsIncompleteInputAtFocusAndLifecycleBoundaries() {
        val input = FabricMinecraftCharacterInput()
        assertNull(input.accept('\uD83D'))
        input.reset()
        input.reset()
        assertNull(input.accept('\uDE42'))
        assertEquals(TextInputEvent.Character('한'.code), input.accept('한'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
    }
}
