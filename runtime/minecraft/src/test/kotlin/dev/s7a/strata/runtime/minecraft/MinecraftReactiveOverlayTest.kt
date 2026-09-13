package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Checks overlapping retained siblings against hand-calculated source-over pixels, including erased lower content.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftReactiveOverlayTest {
    @Test
    fun changingAndClearingTheLowerLayerPreservesOpaqueAndTranslucentForegrounds() {
        val color = ReactiveTestSource(ArgbColor(0xFFFF0000.toInt()))
        val definition =
            ScreenDefinition("Overlapping reactive siblings") {
                Stack(Modifier.Empty.size(8, 8).background(ArgbColor(0xFF000000.toInt()))) {
                    Observe(color) { current -> Spacer(Modifier.Empty.size(8, 8).background(current)) }
                    Spacer(Modifier.Empty.size(4, 4).background(ArgbColor(0x80FFFFFF.toInt())), key = ElementKey("translucent"))
                    Spacer(Modifier.Empty.size(2, 2).background(ArgbColor(0xFF00FF00.toInt())), key = ElementKey("opaque"))
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val before = host.frame(IntSize(8, 8))
            assertPixels(rasterizeHeadless(before.drawCommands, before.size), 0xFFFF0000.toInt(), 0xFFFF8080.toInt())
            host.startRenderMonitoring().use { monitor ->
                val foreground = listOf("translucent", "opaque").flatMap { monitor.findNodes(ElementKey(it)) }
                listOf(
                    Triple(0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF8080FF.toInt()),
                    Triple(0, 0xFF000000.toInt(), 0xFF808080.toInt()),
                    Triple(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF8080.toInt()),
                ).forEach { (source, background, blended) ->
                    monitor.checkpoint()
                    color.publish(ArgbColor(source))
                    val frame = host.frame(IntSize(8, 8))
                    assertPixels(rasterizeHeadless(frame.drawCommands, frame.size), background, blended)
                    val snapshot = monitor.snapshot()
                    assertEquals(1L, snapshot.counts[UiRenderMetric.ObserveEvaluation])
                    assertEquals(1L, snapshot.counts[UiRenderMetric.Paint])
                    assertEquals(0L, snapshot.counts[UiRenderMetric.Measure])
                    assertEquals(0L, snapshot.counts[UiRenderMetric.Layout])
                    foreground.forEach { id -> assertEquals(0L, snapshot.nodes.single { it.id == id }.counts[UiRenderMetric.NodeUpdate]) }
                    assertEquals(foreground, listOf("translucent", "opaque").flatMap { monitor.findNodes(ElementKey(it)) })
                    monitor.checkpoint()
                    color.publish(ArgbColor(source))
                    assertSame(frame, host.frame(IntSize(8, 8)))
                    assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.ContentEvaluation])
                    assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.Paint])
                }
            }
        }
    }

    private fun assertPixels(
        image: HeadlessImage,
        background: Int,
        blended: Int,
    ) {
        repeat(8) { y ->
            repeat(8) { x ->
                val expected =
                    when {
                        x < 2 && y < 2 -> 0xFF00FF00.toInt()
                        x < 4 && y < 4 -> blended
                        else -> background
                    }
                assertEquals(expected, image.argbAt(x, y), "Pixel ($x, $y)")
            }
        }
    }
}
