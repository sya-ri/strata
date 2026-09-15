package dev.s7a.strata

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.internal.platform.appendScalar
import dev.s7a.strata.internal.platform.scalarAt
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.parseProfileUuid
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Runs deterministic low-level portability contracts on JVM and JavaScript.
 */
internal class PortableContractsTest {
    @Test
    fun geometryAndImageBoundariesRejectWrappedValues() {
        assertFailsWith<ArithmeticException> { IntOffset(Int.MAX_VALUE, 0) + IntOffset(1, 0) }
        assertFailsWith<ArithmeticException> { IntOffset(Int.MIN_VALUE, 0) - IntOffset(1, 0) }
        assertFailsWith<ArithmeticException> { IntRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1) }
        assertFailsWith<ArithmeticException> { LongRect(Long.MIN_VALUE, 0L, Long.MAX_VALUE, 1L) }
        assertFailsWith<ArithmeticException> { Insets(left = Int.MAX_VALUE, right = 1) }
        assertFailsWith<ArithmeticException> { createDrawImage(IntSize(65_536, 65_536), intArrayOf()) }
        assertEquals(Long.MAX_VALUE, LongRect(Long.MIN_VALUE, 0L, -1L, 1L).width)
        val image = createDrawImage(IntSize(2, 2), intArrayOf(1, 2, 3, 4))
        assertEquals(4, image.argbAt(1, 1))
        assertFailsWith<IllegalArgumentException> { image.argbAt(2, 1) }
    }

    @Test
    @OptIn(InternalStrataRuntimeApi::class)
    fun parentDataChecksTheRuntimeClassBeforeItsErasedCast() {
        val key = ParentDataKey(Int::class)
        assertEquals(7, key.castErased(7))
        assertFailsWith<IllegalArgumentException> { key.castErased("7") }
    }

    @Test
    fun unicodeScalarsPreserveSupplementaryAndIsolatedSurrogates() {
        val text = StringBuilder().appendScalar(0x1F600).appendScalar(0x3042).toString()
        assertEquals(3, text.length)
        assertEquals(0x1F600, text.scalarAt(0))
        assertEquals(0x3042, text.scalarAt(2))
        // The JS compiler may replace an isolated surrogate literal while serializing its intermediate representation.
        val isolated = charArrayOf(0xD800.toChar()).concatToString()
        assertEquals(0xD800, isolated.scalarAt(0))
        assertFailsWith<IllegalArgumentException> { StringBuilder().appendScalar(0xD800) }
    }

    @Test
    @OptIn(InternalStrataRuntimeApi::class)
    fun equivalentProfileIdentitiesDoNotInvalidateObservedSkinSources() {
        val text = "01234567-89ab-cdef-8123-456789abcdef"
        val state = mutableStateOf(PlayerSkinSource.Uuid(parseProfileUuid(text)))
        var invalidations = 0
        val observation = StateObservation({}, {}, { invalidations += 1 }, {})
        try {
            observation.evaluate { state.value }
            state.value = PlayerSkinSource.Uuid(parseProfileUuid(text.uppercase()))
            assertEquals(0, invalidations)
            val changed = parseProfileUuid("80000000-0000-0000-ffff-ffffffffffff")
            state.value = PlayerSkinSource.Uuid(changed)
            assertEquals(1, invalidations)
            assertEquals(changed, state.value.value)
        } finally {
            observation.close()
        }
    }
}
