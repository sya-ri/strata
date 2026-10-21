package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Requires the candidate's exclusive array transfer without changing shared semantic and defensive-constructor controls.
 * The copying neutral runtime executes every shared ownership test and omits only this storage-identity class.
 */
internal class MinecraftTextLineStorageTest {
    @Test
    fun ownedAdmissionRetainsExactlyTheTwoExclusiveArraysAndDetachedRun() {
        MinecraftTextRenderer.legacy(emptyMap()).use { renderer ->
            val run = renderer.create(UiText.Literal(""), TextStyle.Normal)
            val offsets = intArrayOf(0, 1, 3, 4)
            val positions = intArrayOf(0, 4, 4, -2)
            val line = MinecraftTextLine.createOwned(0, 4, 6, run, offsets, positions)
            assertSame(offsets, array(line, "offsets"))
            assertSame(positions, array(line, "positions"))
            assertSame(run, line.run)
            val ownedFields = MinecraftTextLine::class.java.declaredFields.filter { field -> Modifier.isStatic(field.modifiers).not() }
            assertEquals(2, ownedFields.count { field -> field.type === IntArray::class.java })
            assertTrue(ownedFields.all { field -> field.type.isPrimitive || field.type === IntArray::class.java || field.type === MinecraftTextRun::class.java || field.type === MinecraftTextInkBounds::class.java })
        }
    }

    private fun array(
        line: MinecraftTextLine,
        name: String,
    ): IntArray =
        MinecraftTextLine::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(line) as IntArray
}
