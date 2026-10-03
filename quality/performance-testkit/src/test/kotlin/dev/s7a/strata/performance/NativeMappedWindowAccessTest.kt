package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * Verifies mapped production routing without a Fabric dependency or accepting missing host members.
 */
internal class NativeMappedWindowAccessTest {
    @Test
    fun resolverRoutesClientAndWindowThroughActualRuntimeNames() {
        val (window, handle) = NativeMappedWindowAccess.resolve(javaClass.classLoader, MappedPerformanceResolver())
        assertIs<MappedPerformanceWindow>(window)
        assertEquals(42L, handle)
    }

    @Test
    fun absentMappedMethodCannotCertifyAValidWindow() {
        assertFails { NativeMappedWindowAccess.resolve(javaClass.classLoader, MappedPerformanceResolver(invalidWindow = true)) }
    }
}
