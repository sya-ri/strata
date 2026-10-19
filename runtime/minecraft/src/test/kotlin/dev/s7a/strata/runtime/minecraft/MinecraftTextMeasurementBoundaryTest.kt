package dev.s7a.strata.runtime.minecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier as ReflectModifier

/**
 * Requires measurement to remain outside the production text lookup and paint contracts.
 */
internal class MinecraftTextMeasurementBoundaryTest {
    @Test
    fun visiblePaintingReturnsNoCounterAndLookupExposesNoDiagnostic() {
        for (type in listOf(MinecraftTextRun::class.java, MinecraftTextLayout::class.java)) {
            val paint =
                type.declaredMethods
                    .filter { method -> ReflectModifier.isStatic(method.modifiers).not() }
                    .associateBy { method -> method.name.substringBefore('$') }
                    .getValue(MinecraftTextRun::paintVisible.name)
            assertEquals(Void.TYPE, paint.returnType)
        }
        assertFalse("boundaryVisits" in MinecraftTextLine::class.java.declaredMethods.map { method -> method.name.substringBefore('$') })
        val bounds =
            MinecraftTextRun::class.java.declaredMethods
                .associateBy { method -> method.name }
                .getValue("hasPreparedTextBounds")
        assertEquals(listOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), bounds.parameterTypes.toList())
    }
}
