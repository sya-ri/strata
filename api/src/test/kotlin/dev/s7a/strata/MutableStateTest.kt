package dev.s7a.strata

import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Checks caller-owned state thread confinement and arbitrary equality failure boundaries.
 */
internal class MutableStateTest {
    @Test
    fun rejectsCrossThreadReadsAndWrites() {
        val state = mutableStateOf(1)
        val read = FutureTask { state.value }
        Thread(read).start()
        val readFailure = assertThrows(ExecutionException::class.java) { read.get(5, TimeUnit.SECONDS) }
        assertEquals(IllegalStateException::class.java, readFailure.cause?.javaClass)
        val write = FutureTask { state.value = 2 }
        Thread(write).start()
        val writeFailure = assertThrows(ExecutionException::class.java) { write.get(5, TimeUnit.SECONDS) }
        assertEquals(IllegalStateException::class.java, writeFailure.cause?.javaClass)
        assertEquals(1, state.value)
    }

    @Test
    fun equalityFailurePreservesTheValueAndRestoresAccess() {
        val failure = IllegalArgumentException("Equality failed")
        val first = EqualityValue { throw failure }
        val state = mutableStateOf(first)
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { state.value = EqualityValue { false } })
        assertSame(first, state.value)
    }

    @Test
    fun equalityCannotReadOrWriteAnotherState() {
        val other = mutableStateOf(0)
        val first =
            EqualityValue {
                assertThrows(IllegalStateException::class.java) { other.value }
                assertThrows(IllegalStateException::class.java) { other.value = 1 }
                false
            }
        val state = mutableStateOf(first)
        val next = EqualityValue { false }
        state.value = next
        assertSame(next, state.value)
        assertEquals(0, other.value)
    }

    private class EqualityValue(
        private val compare: () -> Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is EqualityValue && compare()

        override fun hashCode(): Int = 0
    }
}
