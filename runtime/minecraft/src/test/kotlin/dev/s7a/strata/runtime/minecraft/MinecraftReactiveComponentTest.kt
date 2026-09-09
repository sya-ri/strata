package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Exercises direct arguments on production primitives, including modifier ownership and frame-level work.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftReactiveComponentTest {
    @Test
    fun progressChangesPaintWithoutMeasuringOrLayingOutAncestors() {
        val progress = ReactiveTestSource(0.25)
        val definition =
            ScreenDefinition("progress") {
                Column(key = ElementKey("parent")) { ProgressBar(progress, size = IntSize(80, 12)) }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val before = host.frame(IntSize(80, 40))
            host.startRenderMonitoring().use { monitor ->
                progress.publish(0.75)
                val after = host.frame(IntSize(80, 40))
                assertNotEquals(before.drawCommands, after.drawCommands)
                val snapshot = monitor.snapshot()
                assertEquals(1L, snapshot.counts[UiRenderMetric.StateComponentEvaluation])
                assertEquals(0L, snapshot.counts[UiRenderMetric.Measure])
                assertEquals(0L, snapshot.counts[UiRenderMetric.Layout])
                assertEquals(1L, snapshot.counts[UiRenderMetric.Paint])
            }
        }
    }

    @Test
    fun textWidthChangesPropagateToParentWhileEqualMappedLabelKeepsFrame() {
        val length = ReactiveTestSource(1)
        val label = length.map { if (it < 3) "A" else "Longer" }
        createMinecraftUiHost(
            ScreenDefinition("text") {
                Row(key = ElementKey("parent")) {
                    Text(label)
                    Text("B")
                }
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            val before = host.frame(IntSize(100, 20))
            host.startRenderMonitoring().use { monitor ->
                length.publish(2)
                assertSame(before, host.frame(IntSize(100, 20)))
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.StateComponentEvaluation])
                monitor.checkpoint()
                length.publish(3)
                val after = host.frame(IntSize(100, 20))
                assertNotEquals(before.semantics.last().bounds, after.semantics.last().bounds)
                val parent = monitor.findNodes(ElementKey("parent")).single()
                assertEquals(
                    1L,
                    monitor
                        .snapshot()
                        .nodes
                        .single { it.id == parent }
                        .counts[UiRenderMetric.Measure],
                )
            }
        }
    }

    @Test
    fun sameMappedEnablementDrivesAppearanceAndKeyboardOnTheActualButton() {
        val sending = ReactiveTestSource(false)
        val enabled = sending.map { it.not() }
        val label = sending.map { if (it) "Sending" else "Send" }
        var sent = 0
        createMinecraftUiHost(
            ScreenDefinition("button") {
                Button(label, enabled = enabled, modifier = Modifier.Empty.onActivate(enabled) { sent += 1 }.initialFocus())
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            host.frame(IntSize(150, 20))
            assertEquals(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, scanCode = 0)))
            assertEquals(1, sent)
            sending.publish(true)
            // Input still uses the previous committed frame until the next host frame.
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, scanCode = 0))
            assertEquals(2, sent)
            assertEquals(
                UiText.Literal("Sending"),
                host
                    .frame(IntSize(150, 20))
                    .semantics
                    .single()
                    .semantics.label,
            )
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, scanCode = 0))
            assertEquals(2, sent)
            sending.publish(false)
            host.frame(IntSize(150, 20))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, scanCode = 0))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, scanCode = 0))
            assertEquals(3, sent)
            assertEquals(1, sending.subscriptions)
        }
    }

    @Test
    fun clockAndEnabledUpdatesPreserveEditorIdentityDraftAndPreedit() {
        val clock = ReactiveTestSource("12:00")
        val enabled = ReactiveTestSource(true)
        val editor = TextAreaState("draft")
        createMinecraftUiHost(
            ScreenDefinition("editor") {
                Column {
                    Text(clock)
                    TextArea(
                        editor,
                        TextAreaViewport.Size(IntSize(80, 30)),
                        enabled,
                        modifier = Modifier.Empty.initialFocus(),
                        key = ElementKey("editor"),
                    )
                }
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            host.frame(IntSize(100, 60))
            val focus = host.textInputFocus
            assertNotNull(focus)
            host.dispatchTextInput(TextInputEvent.Preedit("A", 1, listOf("A"), 0))
            val before = host.frame(IntSize(100, 60))
            host.startRenderMonitoring().use { monitor ->
                val ids = monitor.findNodes(ElementKey("editor"))
                clock.publish("12:01")
                enabled.publish(true)
                val after = host.frame(IntSize(100, 60))
                assertSame(focus, host.textInputFocus)
                assertEquals(ids, monitor.findNodes(ElementKey("editor")))
                assertEquals(before.semantics.last().semantics, after.semantics.last().semantics)
                assertEquals("draft", editor.value)
                host.dispatchTextInput(TextInputEvent.Character('B'.code))
                host.frame(IntSize(100, 60))
                assertEquals("draftB", editor.value)
            }
        }
    }

    @Test
    fun directComponentModifierStillSuppliesWeightToItsParent() {
        val label = ReactiveTestSource("A")
        createMinecraftUiHost(
            ScreenDefinition("weighted") {
                Row {
                    Text(label, layout = TextLayout.Multiline(), modifier = Modifier.Empty.weight(1f))
                    Text("B", layout = TextLayout.Multiline(), modifier = Modifier.Empty.weight(1f))
                }
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            val frame = host.frame(IntSize(100, 20))
            assertEquals(
                0,
                frame.semantics
                    .first()
                    .bounds.left,
            )
            assertEquals(
                50,
                frame.semantics
                    .last()
                    .bounds.left,
            )
        }
    }
}
