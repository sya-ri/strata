@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCheckedChange
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.modifier.onSliderChange
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Uses public profile-backed hosts to prove retained paint reuse and current input callback ownership.
 */
internal class MinecraftStatefulControlFrameTest {
    @Test
    fun allThreeControlsReuseEquivalentPaintWhileInstallingFreshActions() {
        for (kind in Kind.entries) {
            val revision = mutableStateOf(0)
            val calls = ArrayList<Int>()
            val checkbox = CheckboxState()
            val slider = SliderState(0.0)
            val cycle = CycleButtonState(listOf(0, 1, 2))
            val host =
                createMinecraftUiHost(
                    UiDefinition("control") {
                        val version = revision.value
                        when (kind) {
                            Kind.Checkbox -> Checkbox("A", checkbox, modifier = Modifier.Empty.onCheckedChange { calls.add(version) })
                            Kind.Slider -> Slider("A", slider, modifier = Modifier.Empty.onSliderChange { calls.add(version) })
                            Kind.Cycle -> CycleButton(cycle, modifier = Modifier.Empty.onCycle<Int> { calls.add(version) })
                        }
                    },
                    MinecraftProfileFixture.create(),
                    )
            host.use {
                host.attach()
                val original = host.frame(IntSize(150, 20))
                val oldCommands = original.drawCommands.toList()
                val oldSemantics = original.semantics.toList()
                val pixels = rasterizeHeadless(oldCommands, IntSize(150, 20)).copyArgb()
                val monitor = host.startRenderMonitoring()
                try {
                    revision.value = 1
                    val fresh = host.frame(IntSize(150, 20))
                    assertEquals(0L, monitor.snapshot().counts.getValue(UiRenderMetric.Paint), kind.name)
                    assertEquals(0L, monitor.snapshot().counts.getValue(UiRenderMetric.Measure), kind.name)
                    assertEquals(oldCommands, fresh.drawCommands)
                    assertEquals(oldSemantics, fresh.semantics)
                    assertTrue(pixels.contentEquals(rasterizeHeadless(fresh.drawCommands, IntSize(150, 20)).copyArgb()))
                    assertSame(fresh, host.frame(IntSize(150, 20)))
                    val x = if (kind == Kind.Slider) 149 else 1
                    assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(x, 1), PointerButton.Primary)))
                    host.dispatchPointer(PointerEvent.Release(IntOffset(x, 1), PointerButton.Primary))
                    assertTrue(calls.isNotEmpty())
                    assertTrue(calls.all { it == 1 })
                    val key = if (kind == Kind.Slider) KeyCode.Left else KeyCode.Space
                    assertEquals(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(key, 0)))
                    assertEquals(1, calls.last())
                    host.frame(IntSize(150, 20))
                    assertEquals(oldCommands, original.drawCommands)
                    assertEquals(oldSemantics, original.semantics)
                } finally {
                    monitor.close()
                }
                host.detach()
                host.attach()
                host.frame(IntSize(150, 20))
            }
            host.close()
        }
    }

    @Test
    fun pendingStateInvalidationSurvivesAFreshEquivalentDescription() {
        val revision = mutableStateOf(0)
        val checked = CheckboxState()
        val host =
            createMinecraftUiHost(
                UiDefinition("pending") {
                    revision.value
                    Checkbox("A", checked)
                },
                MinecraftProfileFixture.create(),
                )
        host.use {
            host.attach()
            val original = host.frame(IntSize(150, 20))
            checked.checked = true
            revision.value = 1
            val monitor = host.startRenderMonitoring()
            try {
                val current = host.frame(IntSize(150, 20))
                assertEquals(true, current.semantics.single().semantics.checked)
                assertEquals(false, original.semantics.single().semantics.checked)
                assertEquals(1L, monitor.snapshot().counts.getValue(UiRenderMetric.Paint))
                assertEquals(0xFF393939.toInt(), rasterizeHeadless(current.drawCommands, IntSize(150, 20)).argbAt(0, 0))
                assertEquals(0xFF191919.toInt(), rasterizeHeadless(original.drawCommands, IntSize(150, 20)).argbAt(0, 0))
            } finally {
                monitor.close()
            }
        }
    }

    @Test
    fun sliderCaptureSurvivesEquivalentUpdateAndDisablingStopsItsDrag() {
        val revision = mutableStateOf(0)
        val enabled = mutableStateOf(true)
        val value = SliderState(0.0)
        val calls = ArrayList<Int>()
        val host =
            createMinecraftUiHost(
                UiDefinition("drag") {
                    val version = revision.value
                    Slider("A", value, enabled = enabled.value, modifier = Modifier.Empty.onSliderChange { calls.add(version) })
                },
                MinecraftProfileFixture.create(),
                )
        host.use {
            host.attach()
            host.frame(IntSize(150, 20))
            host.dispatchPointer(PointerEvent.Press(IntOffset(4, 1), PointerButton.Primary))
            revision.value = 1
            host.frame(IntSize(150, 20))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(149, 1), PointerButton.Primary, 145.0, 0.0))
            assertEquals(1.0, value.value)
            assertEquals(1, calls.last())
            enabled.value = false
            host.frame(IntSize(150, 20))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(0, 1), PointerButton.Primary, -149.0, 0.0))
            assertEquals(1.0, value.value)
            host.resetInputState()
            assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)))
        }
    }

    @Test
    fun keyedReorderAndRemovalKeepCorrectStatesAndOldFrames() {
        val order = mutableStateOf(listOf(0, 1, 2))
        val states = listOf(CheckboxState(false), CheckboxState(true), CheckboxState(false))
        val host =
            createMinecraftUiHost(
                UiDefinition("keyed controls") {
                    Column {
                        for (index in order.value) Checkbox("$index", states[index], key = ElementKey(index))
                    }
                },
                MinecraftProfileFixture.create(),
                )
        host.use {
            host.attach()
            val original = host.frame(IntSize(150, 60))
            val commands = original.drawCommands.toList()
            val semantics = original.semantics.toList()
            order.value = listOf(2, 1, 0)
            val moved = host.frame(IntSize(150, 60))
            assertEquals(listOf("2", "1", "0"), moved.semantics.map { (it.semantics.label as UiText.Literal).value })
            assertEquals(listOf(false, true, false), moved.semantics.map { it.semantics.checked })
            val monitor = host.startRenderMonitoring()
            try {
                order.value = listOf(2, 0)
                val removed = host.frame(IntSize(150, 60))
                assertEquals(listOf("2", "0"), removed.semantics.map { (it.semantics.label as UiText.Literal).value })
                assertEquals(1L, monitor.snapshot().counts.getValue(UiRenderMetric.NodeDispose))
                states[1].checked = false
                assertSame(removed, host.frame(IntSize(150, 60)))
                assertEquals(commands, original.drawCommands)
                assertEquals(semantics, original.semantics)
            } finally {
                monitor.close()
            }
        }
    }

    @Test
    fun structuralPaddingPreservesTheControlAndMovesOnlyItsEffectiveGeometry() {
        val padded = mutableStateOf(false)
        val checked = CheckboxState()
        val host =
            createMinecraftUiHost(
                UiDefinition("padding") {
                    Checkbox("A", checked, modifier = if (padded.value) Modifier.Empty.padding(2) else Modifier.Empty)
                },
                MinecraftProfileFixture.create(),
                )
        host.use {
            host.attach()
            val original = host.frame(IntSize(150, 20))
            val commands = original.drawCommands.toList()
            padded.value = true
            val current = host.frame(IntSize(154, 24))
            assertEquals(IntRect(2, 2, 152, 22), current.semantics.single().bounds)
            assertEquals(0, rasterizeHeadless(current.drawCommands, IntSize(154, 24)).argbAt(0, 0))
            assertEquals(0xFF191919.toInt(), rasterizeHeadless(current.drawCommands, IntSize(154, 24)).argbAt(2, 2))
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary)))
            assertEquals(true, checked.checked)
            assertEquals(commands, original.drawCommands)
        }
    }

    @Test
    fun freshFailingActionsPreservePrimaryFailureAndReleaseEveryControlHost() {
        for (kind in Kind.entries) {
            val revision = mutableStateOf(0)
            val failure = IllegalStateException("Current control callback failed")
            val checkbox = CheckboxState()
            val slider = SliderState(0.0)
            val cycle = CycleButtonState(listOf(0, 1, 2))
            val host =
                createMinecraftUiHost(
                    UiDefinition("failure") {
                        val version = revision.value
                        when (kind) {
                            Kind.Checkbox -> Checkbox("A", checkbox, modifier = Modifier.Empty.onCheckedChange { if (version == 1) throw failure })
                            Kind.Slider -> Slider("A", slider, modifier = Modifier.Empty.onSliderChange { if (version == 1) throw failure })
                            Kind.Cycle -> CycleButton(cycle, modifier = Modifier.Empty.onCycle<Int> { if (version == 1) throw failure })
                        }
                    },
                    MinecraftProfileFixture.create(),
                    )
            try {
                host.attach()
                val original = host.frame(IntSize(150, 20))
                val semantics = original.semantics.toList()
                revision.value = 1
                host.frame(IntSize(150, 20))
                val x = if (kind == Kind.Slider) 149 else 1
                assertSame(failure, assertThrows(IllegalStateException::class.java) { host.dispatchPointer(PointerEvent.Press(IntOffset(x, 1), PointerButton.Primary)) })
                val evaluator = host.javaClass.getDeclaredField("evaluator")
                evaluator.isAccessible = true
                assertEquals(null, evaluator.get(host))
                assertEquals(semantics, original.semantics)
            } finally {
                host.close()
                host.close()
            }
        }
    }

    /**
     * Public controls with independent input and semantics behavior.
     */
    private enum class Kind {
        Checkbox,
        Slider,
        Cycle,
    }
}
