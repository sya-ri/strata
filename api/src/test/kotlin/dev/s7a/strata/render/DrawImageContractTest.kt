package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

/**
 * Verifies the immutable public image value and its intentionally narrow JVM surface.
 */
internal class DrawImageContractTest {
    @Test
    fun zeroAndPositiveImagesValidateAndPreserveValueSemantics() {
        val empty = createDrawImage(IntSize(0, 4), intArrayOf())
        val emptyHeight = createDrawImage(IntSize(4, 0), intArrayOf())
        val emptyBoth = createDrawImage(IntSize(0, 0), intArrayOf())
        assertEquals(IntSize(0, 4), empty.size)
        assertEquals(IntSize(4, 0), emptyHeight.size)
        assertEquals(IntSize(0, 0), emptyBoth.size)
        assertArrayEquals(intArrayOf(), empty.copyArgb())

        val pixels = intArrayOf(0xFF010203.toInt(), 0x80405060.toInt())
        val first = createDrawImage(IntSize(2, 1), pixels)
        val second = createDrawImage(IntSize(2, 1), pixels.copyOf())
        val shapeDifferentPixels = first.copyArgb()
        pixels[0] = 0

        assertEquals(0xFF010203.toInt(), first.argbAt(0, 0))
        assertEquals(first, first)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, createDrawImage(IntSize(1, 2), shapeDifferentPixels))
        assertNotEquals(first, createDrawImage(IntSize(2, 1), intArrayOf(0, 0)))
        val nullImage: Any? = null
        assertFalse(first.equals(nullImage))
        assertFalse(first.equals("not an image"))
        val copy = first.copyArgb()
        assertNotSame(copy, first.copyArgb())
        copy[0] = 0
        assertEquals(0xFF010203.toInt(), first.argbAt(0, 0))
    }

    @Test
    fun areaAndCoordinateChecksAreExplicit() {
        assertThrows<IllegalArgumentException> {
            createDrawImage(IntSize(2, 2), intArrayOf(1, 2, 3))
        }
        assertThrows<ArithmeticException> {
            createDrawImage(IntSize(Int.MAX_VALUE, 2), intArrayOf())
        }
        val image = createDrawImage(IntSize(2, 2), IntArray(4))
        listOf(
            { image.argbAt(-1, 0) },
            { image.argbAt(2, 0) },
            { image.argbAt(0, -1) },
            { image.argbAt(0, 2) },
        ).forEach { read ->
            assertThrows<IllegalArgumentException> { read() }
        }
        listOf(emptyImage(IntSize(0, 4)), emptyImage(IntSize(4, 0)), emptyImage(IntSize(0, 0))).forEach { zero ->
            assertThrows<IllegalArgumentException> { zero.argbAt(0, 0) }
        }
    }

    @Test
    fun immutablePixelsRemainSafeAcrossConcurrentReadsAndCopies() {
        val image = createDrawImage(IntSize(2, 1), intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt()))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (0 until 4).map {
                    executor.submit {
                        repeat(100) {
                            assertEquals(0xFF010203.toInt(), image.argbAt(0, 0))
                            assertArrayEquals(intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt()), image.copyArgb())
                        }
                    }
                }
            futures.forEach { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun imageAndFactoryExposeOnlyTheDeclaredPublicSurface() {
        val imageType = DrawImage::class.java
        assertTrue(imageType.isSealed)
        assertTrue(imageType.isInterface)
        assertEquals(setOf("getSize", "argbAt", "copyArgb"), imageType.declaredMethods.map { method -> method.name }.toSet())
        assertTrue(imageType.declaredConstructors.isEmpty())
        assertTrue(imageType.declaredFields.isEmpty())
        assertTrue(imageType.declaredClasses.isEmpty())
        imageType.declaredMethods.forEach { method ->
            assertTrue(Modifier.isPublic(method.modifiers))
            assertTrue(Modifier.isAbstract(method.modifiers))
            assertFalse(method.isSynthetic)
        }
        val implementations = imageType.permittedSubclasses.toList()
        assertEquals(1, implementations.size)
        val implementation = implementations.single()
        assertFalse(Modifier.isPublic(implementation.modifiers))
        assertFalse(Modifier.isProtected(implementation.modifiers))
        assertTrue(
            implementation.declaredConstructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers) && constructor.isSynthetic.not()
            },
        )
        val pixelFields = implementation.declaredFields.filter { field -> field.type == IntArray::class.java }
        assertEquals(1, pixelFields.size)
        assertTrue(Modifier.isPrivate(pixelFields.single().modifiers))
        val sizeFields = implementation.declaredFields.filter { field -> field.type == IntSize::class.java }
        assertEquals(1, sizeFields.size)
        assertTrue(Modifier.isPrivate(sizeFields.single().modifiers))

        val factoryName = "${imageType.packageName}.DrawImages"
        val factory = imageType.classLoader.loadClass(factoryName)
        val methods =
            factory.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && method.isSynthetic.not()
            }
        assertEquals(1, methods.size)
        val method = methods.single()
        assertEquals("createDrawImage", method.name)
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(DrawImage::class.java, method.returnType)
        assertEquals(listOf(IntSize::class.java, IntArray::class.java), method.parameterTypes.toList())
    }

    private fun emptyImage(size: IntSize): DrawImage = createDrawImage(size, intArrayOf())
}
