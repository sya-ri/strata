package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.Collections
import dev.s7a.strata.runtime.platform.IdentityMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Checks identity ownership and detached diagnostic collection contracts on JVM and JavaScript.
 */
internal class PortableRegistryTest {
    @Test
    fun equalKeysRemainIndependentAndSnapshotsSurviveRelease() {
        val first = listOf(1)
        val second = listOf(1)
        val registry = IdentityMap<List<Int>, String>()
        registry[first] = "first"
        registry[second] = "second"
        val values = registry.values
        assertEquals("first", registry.remove(first))
        assertNull(registry[first])
        assertEquals("second", registry[second])
        registry.clear()
        assertEquals(emptyList(), registry.values)
        assertEquals(setOf("first", "second"), values.toSet())
    }

    @Test
    fun diagnosticMapsPreserveValueEqualityAndRejectMutation() {
        val source = mutableMapOf("count" to 1L)
        val snapshot = Collections.unmodifiableMap(source.toMap())
        source["count"] = 2L
        assertEquals(mapOf("count" to 1L), snapshot)
        assertEquals(mapOf("count" to 1L).hashCode(), snapshot.hashCode())
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap)["count"] = 3L }
        assertEquals(1L, snapshot["count"])
    }
}
