package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**

 * Verifies appearance pixels and retained input through the production DSL, host, and monitoring path.

 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextInputAppearanceTest {
    @Test
    fun defaultAppearanceKeepsLegacyPixelsForBothEditors() {
        val size = IntSize(80, 30)
        listOf(false, true).forEach { multiline ->
            val profile = MinecraftProfileFixture.create()

            fun render(explicit: Boolean): IntArray {
                val field = TextFieldState("A")
                val area = TextAreaState("A\nB")
                val definition =
                    ScreenDefinition("default compatibility") {
                        val focus = Modifier.Empty.initialFocus()
                        when {
                            multiline && explicit -> TextArea(area, TextInputAppearance.Default, TextAreaViewport.Size(size), modifier = focus)
                            multiline -> TextArea(area, TextAreaViewport.Size(size), modifier = focus)
                            explicit -> TextField(field, TextInputAppearance.Default, size, modifier = focus)
                            else -> TextField(field, size, modifier = focus)
                        }
                    }
                return createMinecraftUiHost(definition, profile).use { host ->
                    host.attach()
                    val frame = host.frame(size)
                    rasterizeHeadless(frame.drawCommands, size).copyArgb()
                }
            }
            assertArrayEquals(render(false), render(true))
        }
    }

    @Test
    fun transparentCustomFrameRevealsParentAndSelectsNormalImageWithoutFocus() {
        val size = IntSize(80, 30)
        val transparent = appearance().copy(normal = image(0))
        val background = ArgbColor(0xFFB7CDE3.toInt())
        createMinecraftUiHost(
            ScreenDefinition("transparent frame") {
                TextField(TextFieldState(), transparent, size, modifier = Modifier.Empty.background(background))
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            val frame = host.frame(size)
            val pixels = rasterizeHeadless(frame.drawCommands, size)
            assertEquals(background.value, pixels.argbAt(0, 0))
            assertEquals(background.value, pixels.argbAt(40, 15))
            assertEquals(null, host.textInputFocus)
        }
    }

    @Test
    fun singleLineAndMultilineAppearanceChangesOnlyPaintAndKeepComposition() {
        listOf(false, true).forEach { multiline -> verifyRetainedAppearance(multiline) }
    }

    @Test
    fun invalidCustomNineSliceIsRejectedDuringEvaluation() {
        val invalid = appearance().copy(border = Insets.all(2))
        createMinecraftUiHost(
            ScreenDefinition("invalid") { TextField(TextFieldState(), invalid) },
            MinecraftProfileFixture.create(),
        ).use { host ->
            assertThrows(IllegalArgumentException::class.java) { host.attach() }
        }
    }

    private fun verifyRetainedAppearance(multiline: Boolean) {
        val style = ReactiveTestSource(appearance())
        val enabled = ReactiveTestSource(true)
        val field = TextFieldState("A")
        val area = TextAreaState("A")
        val size = IntSize(80, 30)
        val key = ElementKey("input")
        val definition =
            ScreenDefinition("appearance") {
                Observe(style) { current ->
                    if (multiline) {
                        TextArea(area, current, TextAreaViewport.Size(size), enabled = enabled, textStyle = TextStyle.ContainerLabel, modifier = Modifier.Empty.initialFocus(), key = key)
                    } else {
                        TextField(field, current, size, enabled = enabled, textStyle = TextStyle.ContainerLabel, modifier = Modifier.Empty.initialFocus(), key = key)
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val initial = host.frame(size)
            assertEquals(focused, rasterizeHeadless(initial.drawCommands, size).argbAt(0, 0))
            val focus = host.textInputFocus
            host.dispatchTextInput(TextInputEvent.Preedit("B", 1, listOf("B"), 0))
            val composed = host.frame(size)
            assertTrue(composed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().any { it.color == underline })
            val offset = area.scrollState.metrics.offset
            host.startRenderMonitoring().use { monitor ->
                val nodes = monitor.findNodes(key)
                val changed = appearance().copy(focused = image(0xFFE0F0E0.toInt()), caretColor = ArgbColor(0xFF102010.toInt()))
                style.publish(changed)
                val after = host.frame(size)
                assertEquals(0xFFE0F0E0.toInt(), rasterizeHeadless(after.drawCommands, size).argbAt(0, 0))
                assertSame(focus, host.textInputFocus)
                assertEquals(nodes, monitor.findNodes(key))
                assertEquals(offset, area.scrollState.metrics.offset)
                assertEquals(composed.semantics, after.semantics)
                assertTrue(after.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().any { it.color == underline })
                assertTrue(after.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().any { it.color == changed.caretColor })
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.Measure])
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.Layout])
                monitor.checkpoint()
                repeat(100) { assertSame(after, host.frame(size)) }
                style.publish(changed.copy())
                assertSame(after, host.frame(size))
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.Paint])
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.ObserveEvaluation])
                host.dispatchTextInput(TextInputEvent.Character('C'.code))
                host.frame(size)
                assertEquals("AC", if (multiline) area.value else field.value)
                enabled.publish(false)
                val disabledFrame = host.frame(size)
                assertEquals(disabled, rasterizeHeadless(disabledFrame.drawCommands, size).argbAt(0, 0))
                assertEquals(null, host.textInputFocus)
            }
        }
    }

    private fun appearance(): TextInputAppearance.Custom = TextInputAppearance.Custom(image(0xFFF1F3F4.toInt()), image(focused), ArgbColor(0xFF203020.toInt()), image(disabled), compositionUnderlineColor = underline)

    private fun image(color: Int): ImageSource = ImageSource.Pixels(createDrawImage(IntSize(3, 3), IntArray(9) { color }))

    private val focused = 0xFFD0E0D0.toInt()
    private val disabled = 0xFFE0E0E0.toInt()
    private val underline = ArgbColor(0xFF106030.toInt())
}
