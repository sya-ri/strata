package dev.s7a.strata.runtime

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Observes the JVM collector's optional identity storage outside any timing or allocation measurement.
 */
internal class FailureAccumulatorStorageTest {
    @Test
    fun identityStorageRemainsAbsentUntilTheFirstFailure() {
        val accumulator = FailureAccumulator()
        val storage = FailureAccumulator::class.java.getDeclaredField("seen").apply { isAccessible = true }
        assertNull(storage.get(accumulator))

        repeat(128) {
            accumulator.capture { }
            accumulator.addOptional(null)
            accumulator.throwIfPresent()
        }
        assertNull(storage.get(accumulator))

        val failure = IllegalStateException("first")
        accumulator.add(failure)
        val identities = assertNotNull(storage.get(accumulator))
        accumulator.add(failure)
        assertSame(identities, storage.get(accumulator))
    }

    @Test
    fun anInitialFailureCreatesOperationOwnedStorageImmediately() {
        val failure = IllegalStateException("initial")
        val accumulator = FailureAccumulator(failure)
        val storage = FailureAccumulator::class.java.getDeclaredField("seen").apply { isAccessible = true }

        assertNotNull(storage.get(accumulator))
        assertSame(failure, accumulator.first)
    }
}
