@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Verifies value equality, nested owner isolation, and restoration on JVM and JavaScript.
 */
internal class ExecutionOwnerIdTest {
    @Test
    fun capturedIdentitiesRemainStableAcrossNestedOwnersAndBoxing() {
        val physicalOwner = RuntimeExecutionOwner.current()
        val owner = RuntimeExecutionOwner()
        val nested = RuntimeExecutionOwner()
        val identity =
            owner.run {
                val captured = RuntimeExecutionOwner.current()
                assertNotEquals(physicalOwner, captured)
                assertEquals(captured, owner.run { RuntimeExecutionOwner.current() })
                nested.run { assertNotEquals(captured, RuntimeExecutionOwner.current()) }
                assertEquals(setOf(captured), setOf(RuntimeExecutionOwner.current()))
                captured
            }
        assertEquals(identity, owner.run { RuntimeExecutionOwner.current() })
        assertEquals(physicalOwner, RuntimeExecutionOwner.current())
    }
}
