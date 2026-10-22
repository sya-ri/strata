@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Checks live first-match selection reuse through the public host, including missed callbacks and replacement.
 */
internal class MinecraftCycleButtonSelectionTest {
    @Test
    fun unchangedHoverPaintDoesNotCompareAnyOptions() {
        var comparisons = 0
        val values = List(1_024) { Choice(it) { comparisons += 1 } }
        val state = CycleButtonState(values, values.last())
        host(state).use { host ->
            host.attach()
            val first = host.frame(SIZE)
            comparisons = 0
            host.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
            val hovered = host.frame(SIZE)
            host.dispatchPointer(PointerEvent.Move(IntOffset(90, 2)))
            val unhovered = host.frame(SIZE)
            assertEquals(0, comparisons)
            assertSame(first.semantics.single().semantics, hovered.semantics.single().semantics)
            assertArrayEquals(pixels(first), pixels(unhovered))
            assertLabel(hovered, "V001023")
        }
    }

    @Test
    fun changedSelectionIsResolvedOnceForPaintAndSemantics() {
        var comparisons = 0
        val values = List(128) { Choice(it) { comparisons += 1 } }
        val state = CycleButtonState(values)
        host(state).use { host ->
            host.attach()
            host.frame(SIZE)
            state.value = values.last()
            comparisons = 0
            assertLabel(host.frame(SIZE), "V000127")
            assertEquals(128, comparisons)
        }
    }

    @Test
    fun acceptedAliasesPreserveDirectionalFirstMatch() {
        val first = AliasChoice(0, setOf(0))
        val last = AliasChoice(1, setOf(1))
        val laterAlias = AliasChoice(2, setOf(1))
        val earlierAlias = AliasChoice(3, setOf(0, 1))
        val state = CycleButtonState(listOf(first, last), laterAlias)
        val host = createMinecraftUiHost(UiDefinition("Aliases") { Stack { CycleButton(state, width = 80, label = { UiText.Literal("V${it.id}") }) } }, MinecraftProfileFixture.create())
        host.use {
            host.attach()
            assertLabel(host.frame(SIZE), "V1")
            state.value = earlierAlias
            assertSame(earlierAlias, state.value)
            assertLabel(host.frame(SIZE), "V0")
            state.value = laterAlias
            assertLabel(host.frame(SIZE), "V1")
        }
    }

    @Test
    fun equalDistinctNoOpAndInvalidWritesPreserveTheLiveObjectAndPixels() {
        val values = List(8) { Choice(it) {} }
        val state = CycleButtonState(values, values.last())
        var notifications = 0
        state.observe { notifications += 1 }.use { _ ->
            host(state).use { host ->
                host.attach()
                val first = host.frame(SIZE)
                state.value = Choice(7) {}
                assertSame(values.last(), state.value)
                assertEquals(0, notifications)
                assertThrows(IllegalArgumentException::class.java) { state.value = Choice(8) {} }
                host.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
                host.frame(SIZE)
                host.dispatchPointer(PointerEvent.Move(IntOffset(90, 2)))
                assertArrayEquals(pixels(first), pixels(host.frame(SIZE)))
                assertEquals(0, notifications)
            }
        }
    }

    @Test
    fun repaintReadsTheLiveSelectionAfterEarlierObserverFailure() {
        val values = List(8) { Choice(it) {} }
        val state = CycleButtonState(values)
        val failure = IllegalArgumentException("earlier observer")
        state.observe { throw failure }.use { _ ->
            host(state).use { host ->
                host.attach()
                host.frame(SIZE)
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { state.value = values.last() })
                host.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
                val painted = host.frame(SIZE)
                host(values.let { CycleButtonState(it, it.last()) }).use { reference ->
                    reference.attach()
                    reference.frame(SIZE)
                    reference.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
                    assertArrayEquals(pixels(reference.frame(SIZE)), pixels(painted))
                }
            }
        }
    }

    @Test
    fun observerAndActionReentryUseTheirFinalSelection() {
        val values = List(8) { Choice(it) {} }
        val state = CycleButtonState(values)
        val actions = ArrayList<Choice>()
        val host =
            host(
                state,
                Modifier.onCycle<Choice>(
                    { value ->
                        actions.add(value)
                        state.value = values[6]
                    },
                ),
            )
        host.use {
            host.attach()
            host.frame(SIZE)
            var nested = false
            state
                .observe {
                    if (nested.not()) {
                        nested = true
                        state.value = values[7]
                    }
                }.use { _ ->
                    state.value = values[2]
                    assertLabel(host.frame(SIZE), "V000007")
                }
            host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary))
            assertEquals(listOf(values[0]), actions)
            assertLabel(host.frame(SIZE), "V000006")
        }
    }

    @Test
    fun stateOrderLabelsAndWidthReplacementUseCurrentDeclarations() {
        val values = List(8) { Choice(it) {} }
        val state = mutableStateOf(CycleButtonState(values, values.last()))
        val labelPrefix = mutableStateOf("A")
        val width = mutableStateOf(80)
        val host = createMinecraftUiHost(UiDefinition("Replacement") { Stack { CycleButton(state.value, width = width.value, label = { UiText.Literal(labelPrefix.value + it.id) }) } }, MinecraftProfileFixture.create())
        host.use {
            host.attach()
            assertLabel(host.frame(SIZE), "A7")
            state.value = CycleButtonState(values.reversed(), values.last())
            assertLabel(host.frame(SIZE), "A7")
            labelPrefix.value = "B"
            width.value = 90
            assertLabel(host.frame(IntSize(100, 20)), "B7")
            state.value.value = values.first()
            assertLabel(host.frame(IntSize(100, 20)), "B0")
        }
    }

    @Test
    fun detachedMutationUsesCurrentSelectionOnReattachment() {
        val values = List(8) { Choice(it) {} }
        val state = CycleButtonState(values)
        host(state).use { host ->
            host.attach()
            host.frame(SIZE)
            host.detach()
            state.value = values.last()
            host.attach()
            assertLabel(host.frame(SIZE), "V000007")
        }
    }

    private class Choice(
        val id: Int,
        private val compared: () -> Unit,
    ) {
        override fun equals(other: Any?): Boolean {
            compared()
            return other is Choice && id == other.id
        }

        override fun hashCode(): Int = id
    }

    private class AliasChoice(
        val id: Int,
        private val matches: Set<Int>,
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is AliasChoice && other.id in matches)

        override fun hashCode(): Int = id
    }

    private fun host(
        state: CycleButtonState<Choice>,
        modifier: Modifier = Modifier,
    ): MinecraftUiHost = createMinecraftUiHost(UiDefinition("Selection") { Stack { CycleButton(state, width = 80, modifier = modifier, label = { UiText.Literal("V" + it.id.toString().padStart(6, '0')) }) } }, MinecraftProfileFixture.create())

    private fun assertLabel(
        frame: RuntimeUiFrame,
        value: String,
    ) {
        assertEquals(
            UiText.Literal(value),
            frame.semantics
                .single()
                .semantics.label,
        )
        assertEquals(
            UiText.Literal(value),
            frame.semantics
                .single()
                .semantics.value,
        )
    }

    private fun pixels(frame: RuntimeUiFrame): IntArray = rasterizeHeadless(frame.drawCommands, SIZE).copyArgb()

    private companion object {
        val SIZE = IntSize(100, 20)
    }
}
