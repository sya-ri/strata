package dev.s7a.strata.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies Unicode scalar and detached preedit value contracts.
 */
internal class TextInputEventTest {
    @Test
    fun charactersValidateScalarsAndProduceExactUtf16() {
        assertEquals("A", TextInputEvent.Character(0x41).asString())
        assertEquals("🙂", TextInputEvent.Character(0x1F642).asString())
        listOf(-1, 0xD800, 0xDFFF, 0x110000).forEach { codePoint ->
            assertThrows(IllegalArgumentException::class.java) { TextInputEvent.Character(codePoint) }
        }
    }

    @Test
    fun preeditSnapshotsBlocksAndValidatesIndices() {
        val source = arrayListOf("first", "second")
        val event = TextInputEvent.Preedit("composition", 4, source, 1)
        source.clear()
        assertEquals(listOf("first", "second"), event.blocks)
        assertThrows(UnsupportedOperationException::class.java) {
            (event.blocks as MutableList<String>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) { TextInputEvent.Preedit("x", 2, emptyList(), -1) }
        assertThrows(IllegalArgumentException::class.java) { TextInputEvent.Preedit("x", 1, emptyList(), 0) }
    }
}
