package dev.s7a.strata.runtime.headless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Verifies the intentionally narrow Java-visible result and facade surface.
 */
internal class HeadlessApiContractTest {
    @Test
    fun resultTypesAreSealedReadOnlyInterfaces() {
        assertResultInterface(HeadlessImage::class.java, 4)
        assertResultInterface(HeadlessFrame::class.java, 4)
    }

    @Test
    fun resultTypesExposeNoConstructionMembers() {
        listOf(HeadlessImage::class.java, HeadlessFrame::class.java).forEach { type ->
            assertTrue(type.isSealed)
            assertTrue(type.isInterface)
            assertTrue(type.declaredConstructors.isEmpty())
            assertTrue(type.declaredFields.isEmpty())
            assertTrue(type.declaredClasses.isEmpty())
            type.declaredMethods.forEach { method ->
                assertTrue(Modifier.isPublic(method.modifiers))
                assertTrue(Modifier.isAbstract(method.modifiers))
                assertTrue(method.isSynthetic.not())
            }
        }
    }

    private fun assertResultInterface(
        type: Class<*>,
        methodCount: Int,
    ) {
        assertTrue(type.isSealed)
        assertTrue(type.isInterface)
        assertEquals(methodCount, type.declaredMethods.size)
    }
}
