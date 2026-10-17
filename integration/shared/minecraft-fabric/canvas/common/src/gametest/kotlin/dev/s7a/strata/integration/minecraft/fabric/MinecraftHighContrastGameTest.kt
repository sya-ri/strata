@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.ClientPackSource
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Exercises real built-in pack reloads and ordinary UI opening through every loaded-client adapter.
 * Native captures must match fresh headless rendering and change when high contrast is selected.
 */
internal object MinecraftHighContrastGameTest {
    private val viewport = IntSize(320, 240)

    /**
     * Runs on the suite thread, restoring pack selection, screens and HUD state even after a native failure.
     * The caller owns viewport restoration; all Minecraft mutations belong to the client thread.
     */
    fun run(context: MinecraftCanvasTestContext) {
        val selected =
            context.onClient {
                Minecraft
                    .getInstance()
                    .resourcePackRepository.selectedIds
                    .toList()
            }
        check(ClientPackSource.HIGH_CONTRAST_PACK in context.onClient { Minecraft.getInstance().resourcePackRepository.availableIds })
        check((ClientPackSource.HIGH_CONTRAST_PACK in selected).not()) { "The default acceptance profile must start without high contrast." }
        val receipt = context.outputDirectory.resolve("high-contrast.properties")
        Files.deleteIfExists(receipt)
        context.configureViewport(viewport, 1)
        val hidden = context.onClient { context.exchangeHudHidden(true) }
        val outcome =
            runCatching {
                val original = verify(context, Phase.Original)
                reload(context, selected + ClientPackSource.HIGH_CONTRAST_PACK)
                check(context.onClient { ClientPackSource.HIGH_CONTRAST_PACK in Minecraft.getInstance().resourcePackRepository.selectedIds })
                val highContrast = verify(context, Phase.HighContrast)
                check(original.indices.any { original[it].contentEquals(highContrast[it]).not() }) { "The built-in pack must change rendered control pixels." }
            }
        runCanvasTestCleanup(
            outcome.exceptionOrNull(),
            { context.onClient { context.setScreen(null) } },
            { reload(context, selected) },
            { context.onClient { context.exchangeHudHidden(hidden) } },
        )
        outcome.getOrThrow()
        check(
            context.onClient {
                Minecraft
                    .getInstance()
                    .resourcePackRepository.selectedIds
                    .toList() == selected
            },
        )
        Files.writeString(receipt, "pack=high_contrast\nchecked=ordinary-ui-open,normal,disabled,button-hover,slider-hover,headless-pixel-parity,pack-pixel-change,pack-restoration\ntolerance=0\nresult=passed\n")
    }

    private fun verify(
        context: MinecraftCanvasTestContext,
        phase: Phase,
    ): List<IntArray> {
        val session = context.onClient { definition().open() }
        val outcome =
            runCatching {
                context.waitFor { context.currentScreen() is FabricMinecraftScreen && context.hasOverlay().not() }
                val screen = context.onClient { context.currentScreen() as FabricMinecraftScreen }
                Pointer.entries.map { pointer ->
                    context.movePointer(pointer.position)
                    repeat(2) {
                        MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.completedFrames(context, screen))
                    }
                    capture(context, phase, pointer)
                }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.onClient { session.close() } }, { context.onClient { context.setScreen(null) } })
        return outcome.getOrThrow()
    }

    private fun capture(
        context: MinecraftCanvasTestContext,
        phase: Phase,
        pointer: Pointer,
    ): IntArray {
        val expected =
            context.onClient {
                createMinecraftUiHost(definition(), extractMinecraftUiProfile(), fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                    host.attach()
                    host.frame(viewport, FrameTime(0L))
                    host.dispatchPointer(PointerEvent.Move(pointer.position))
                    val frame = host.frame(viewport, FrameTime(0L))
                    rasterizeHeadless(frame.drawCommands, frame.size)
                }
            }
        val name = "high-contrast-${phase.name}-${pointer.name}"
        Files.write(context.outputDirectory.resolve("$name-headless.png"), expected.encodePng())
        val nativePath = context.takeScreenshot("$name-native", viewport)
        val actual: BufferedImage = checkNotNull(ImageIO.read(nativePath.toFile()))
        check(actual.width == viewport.width && actual.height == viewport.height)
        for (y in 0 until viewport.height) {
            for (x in 0 until viewport.width) {
                check(actual.getRGB(x, y) == expected.argbAt(x, y)) { "High contrast $phase/$pointer pixel differs at ($x, $y): $nativePath" }
            }
        }
        return actual.getRGB(0, 0, viewport.width, viewport.height, null, 0, viewport.width)
    }

    private fun definition(): UiDefinition =
        UiDefinition("High contrast regression") {
            Column(Modifier.size(viewport.width, viewport.height).background(ArgbColor(0xFF000000.toInt()))) {
                Button("Enabled")
                Button("Disabled", enabled = false)
                Slider("Slider", SliderState(0.5))
            }
        }

    private fun reload(
        context: MinecraftCanvasTestContext,
        selected: List<String>,
    ) {
        val reloaded =
            context.onClient {
                val minecraft = Minecraft.getInstance()
                minecraft.resourcePackRepository.setSelected(selected)
                minecraft.reloadResourcePacks()
            }
        context.waitFor { reloaded.isDone }
        reloaded.join()
        context.waitFor { context.hasOverlay().not() }
    }

    private enum class Phase {
        Original,
        HighContrast,
    }

    private enum class Pointer(
        val position: IntOffset,
    ) {
        Outside(IntOffset(319, 239)),
        Button(IntOffset(4, 4)),
        Slider(IntOffset(4, 44)),
    }
}
