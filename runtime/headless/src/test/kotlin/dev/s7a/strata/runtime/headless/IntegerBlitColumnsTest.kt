package dev.s7a.strata.runtime.headless

import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Checks exact callback work, the clipped-storage bound and failure isolation of the production map helper.
 */
internal class IntegerBlitColumnsTest {
    @Test
    fun eligibleMapEvaluatesEachAbsoluteColumnOnce() {
        val positions = ArrayList<Int>()
        val map =
            create(29, 93, 64) { x ->
                positions.add(x)
                x * 7
            }
        assertEquals((29 until 93).toList(), positions)
        assertArrayEquals(IntArray(64) { (29 + it) * 7 }, map)
        val owner = Class.forName("dev.s7a.strata.runtime.headless.HeadlessImplementation")
        assertTrue(owner.declaredFields.all { it.type == owner || it.type.isPrimitive })
    }

    @Test
    fun scalarControlsEvaluateNothingAndAllocateNoMap() {
        val controls = listOf(64 to 3, 1024 to 1, 63 to 65, 16385 to 4, 0 to 99)
        for ((width, height) in controls) {
            assertNull(create(0, width, height) { error("Scalar setup must not sample.") })
        }
    }

    @Test
    fun clippedWidthCapsPrimitivePayloadAtSixtyFourKiB() {
        var evaluations = 0
        val map =
            checkNotNull(
                create(51, 51 + 16384, 4) { x ->
                    evaluations += 1
                    x
                },
            )
        assertEquals(16384, evaluations)
        assertEquals(65536, map.size * Int.SIZE_BYTES)
        assertEquals(51, map.first())
        assertEquals(51 + 16383, map.last())
    }

    @Test
    fun failedConstructionPublishesNothingAndCannotPolluteAnotherInvocation() {
        val failure = IllegalStateException("sample failure")
        var evaluations = 0
        val thrown = assertThrows<IllegalStateException> {
            create(0, 64, 64) { x ->
                evaluations += 1
                if (x == 7) throw failure
                x
            }
        }
        assertTrue(thrown === failure)
        assertEquals(8, evaluations)
        assertArrayEquals(IntArray(64) { 100 - it }, create(0, 64, 64) { 100 - it })
    }

    /** Invokes the private production admission helper outside any measured raster operation. */
    private fun create(
        left: Int,
        right: Int,
        rows: Int,
        sourceAt: (Int) -> Int,
    ): IntArray? {
        val owner = Class.forName("dev.s7a.strata.runtime.headless.HeadlessImplementation")
        val integer = checkNotNull(Int::class.javaPrimitiveType)
        val method = owner.getDeclaredMethod("blitColumns", integer, integer, integer, Function1::class.java)
        val singleton = owner.getDeclaredField("INSTANCE")
        assertTrue(method.trySetAccessible())
        assertTrue(singleton.trySetAccessible())
        return try {
            method.invoke(singleton.get(null), left, right, rows, sourceAt) as IntArray?
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }
    }
}
