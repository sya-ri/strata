package dev.s7a.strata

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.tooltip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Validates callback cardinality and timing arguments before runtime dispatch.
 */
internal class TooltipContractTest {
    @Test
    fun negativeDelayDoesNotEvaluateContent() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            Modifier.tooltip(delayMillis = -1) {
                calls += 1
                Spacer()
            }
        }
        assertEquals(0, calls)
        assertThrows(IllegalArgumentException::class.java) { Modifier.tooltip("text", decorated = false, delayMillis = -1) }
    }

    @Test
    fun contentMustProduceExactlyOneRootAndPreservesItsFailure() {
        assertThrows(IllegalArgumentException::class.java) { Modifier.tooltip {} }
        assertThrows(IllegalArgumentException::class.java) {
            Modifier.tooltip {
                Spacer()
                Spacer()
            }
        }
        val failure = UnsupportedOperationException("content")
        assertEquals(failure, assertThrows(UnsupportedOperationException::class.java) { Modifier.tooltip { throw failure } })
    }
}
