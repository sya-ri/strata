package dev.s7a.strata

import dev.s7a.strata.component.CycleButtonState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies typed CycleButton option ownership, formatting, and enum factories.
 */
internal class CycleButtonStateTest {
    @Test
    fun enumFactoryUsesEveryConstantInDeclarationOrderAndDefaultsToName() {
        val state = CycleButtonState(Difficulty.Normal)

        assertEquals(Difficulty.entries.toList(), state.values)
        assertEquals(Difficulty.Normal, state.value)
        assertEquals("Normal", state.format(Difficulty.Normal))
        assertEquals(Difficulty.Hard, state.next())
        assertEquals(Difficulty.Peaceful, state.next())
        assertEquals(Difficulty.Hard, state.previous())
    }

    @Test
    fun enumFactoryAcceptsAnExplicitDisplayConversion() {
        val state = CycleButtonState(Difficulty.Easy) { value -> "Difficulty: ${value.name}" }

        assertEquals("Difficulty: Easy", state.format(Difficulty.Easy))
    }

    @Test
    fun collectionFactorySnapshotsIterationOrderAndFormatsCanonicalValues() {
        val low = PowerLevel(20)
        val high = PowerLevel(80)
        val source = linkedSetOf(low, high)
        var formattedValue: PowerLevel? = null
        val state =
            CycleButtonState(source, high) { value ->
                formattedValue = value
                "${value.watts} W"
            }
        source.clear()

        assertEquals(listOf(low, high), state.values)
        assertEquals("20 W", state.format(PowerLevel(20)))
        assertSame(low, formattedValue)
        assertEquals("80 W", state.format(high))
        assertThrows(IllegalArgumentException::class.java) { state.format(PowerLevel(40)) }
    }

    @Test
    fun formattingIsConfinedToTheCreatingThread() {
        val conversionCalled = AtomicBoolean()
        val state =
            CycleButtonState(Difficulty.Normal) { value ->
                conversionCalled.set(true)
                value.name
            }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failure =
                executor
                    .submit<Throwable?> { runCatching { state.format(Difficulty.Normal) }.exceptionOrNull() }
                    .get(2, TimeUnit.SECONDS)

            assertEquals(IllegalStateException::class.java, failure?.javaClass)
            assertEquals("Component state requires its creator thread.", failure?.message)
            assertFalse(conversionCalled.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun formattingPropagatesConversionFailuresUnchanged() {
        val expected = IllegalArgumentException("Conversion failed.")
        val state = CycleButtonState(listOf(20), 20) { throw expected }

        assertSame(expected, assertThrows(IllegalArgumentException::class.java) { state.format(20) })
    }

    @Test
    fun legacyListConstructorsKeepDefaultObjectFormatting() {
        val selected = PowerLevel(80)
        val state = CycleButtonState(listOf(PowerLevel(20), selected), selected)
        val first = CycleButtonState(listOf(PowerLevel(20), selected))

        assertEquals("PowerLevel(watts=80)", state.format(selected))
        assertEquals(PowerLevel(20), first.value)
    }

    @Test
    fun collectionFactoryKeepsExistingOptionValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            CycleButtonState(emptyList<Int>(), 0) { value -> value.toString() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CycleButtonState(listOf(1, 1), 1) { value -> value.toString() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CycleButtonState(listOf(1, 2), 3) { value -> value.toString() }
        }
    }

    private enum class Difficulty {
        Peaceful,
        Easy,
        Normal,
        Hard,
    }

    private data class PowerLevel(
        val watts: Int,
    )
}
