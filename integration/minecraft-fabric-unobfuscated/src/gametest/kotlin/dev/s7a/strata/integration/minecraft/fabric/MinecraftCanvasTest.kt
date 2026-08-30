@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Runs the native Canvas fixture through Fabric Client GameTest and restores the previous viewport and GUI scale.
 *
 * The runner owns screenshot files; every native resource operation is scheduled on the client render thread.
 * Assertion and infrastructure failures remain primary when viewport restoration also fails.
 */
internal fun runMinecraftCanvasTest(
    context: ClientGameTestContext,
    profile: MinecraftUiProfile,
    output: Path,
) {
    withMinecraftCanvasContext(context, output) { adapter -> MinecraftCanvasGameTest.run(adapter, profile) }
}

/**
 * Runs the real native Canvas/Slot order check while the caller's server-seeded inventory remains active.
 *
 * The runner owns screenshots and restores its previous viewport and GUI scale after independent scene cleanup.
 * The populated inventory slot and integrated world stay owned by the existing synchronization test.
 *
 * @param context Fabric Client GameTest coordinator, borrowed for scheduling and native screenshots.
 * @param profile immutable profile extracted from the active native resource manager.
 * @param output contained build directory receiving newly rendered acceptance screenshots.
 * @param inventoryIndex the caller's populated player inventory slot, never mutated by the rendering scene.
 * @throws Throwable preserving the original scene failure while viewport restoration is still attempted.
 */
internal fun runMinecraftCanvasSlotTest(
    context: ClientGameTestContext,
    profile: MinecraftUiProfile,
    output: Path,
    inventoryIndex: Int,
) {
    withMinecraftCanvasContext(context, output) { adapter -> MinecraftCanvasSlotGameTest.run(adapter, profile, inventoryIndex) }
}

private fun withMinecraftCanvasContext(
    context: ClientGameTestContext,
    output: Path,
    action: (MinecraftCanvasTestContext) -> Unit,
) {
    val previous =
        context.computeOnClient(
            FailableFunction<Minecraft, Triple<Int, Int, Int>, RuntimeException> { minecraft ->
                Triple(minecraft.window.screenWidth, minecraft.window.screenHeight, minecraft.options.guiScale().get())
            },
        )
    val adapter =
        object : MinecraftCanvasTestContext {
            override val outputDirectory: Path = output

            override fun <T : Any> onClient(action: () -> T): T = context.computeOnClient(FailableFunction<Minecraft, T, RuntimeException> { action() })

            override fun setScreen(screen: Screen?) {
                MinecraftClientScreenAccess.setScreen(Minecraft.getInstance(), screen)
            }

            override fun hasOverlay(): Boolean = MinecraftClientScreenAccess.hasOverlay(Minecraft.getInstance())

            override fun pressPointer(
                screen: Screen,
                position: IntOffset,
            ): Boolean = screen.mouseClicked(position.canvasMouseEvent(), false)

            override fun dragPointer(
                screen: Screen,
                position: IntOffset,
                delta: IntOffset,
            ): Boolean = screen.mouseDragged(position.canvasMouseEvent(), delta.x.toDouble(), delta.y.toDouble())

            override fun releasePointer(
                screen: Screen,
                position: IntOffset,
            ): Boolean = screen.mouseReleased(position.canvasMouseEvent())

            override fun setWindowFocused(focused: Boolean) {
                val window = Minecraft.getInstance().window
                MinecraftCanvasWindowTestScope.invoke(window, window.handle(), focused)
            }

            override fun configureViewport(
                size: IntSize,
                guiScale: Int,
            ) {
                resizeMinecraftCanvasTestWindow(context, size)
                onClient {
                    val minecraft = Minecraft.getInstance()
                    minecraft.options.guiScale().set(guiScale)
                    minecraft.resizeGui()
                }
            }

            override fun waitFor(condition: () -> Boolean) {
                context.waitFor(Predicate<Minecraft> { condition() })
            }

            override fun waitTicks(ticks: Int) {
                context.waitTicks(ticks)
            }

            override fun takeScreenshot(
                name: String,
                size: IntSize,
            ): Path =
                context.takeScreenshot(
                    TestScreenshotOptions
                        .of(name)
                        .disableCounterPrefix()
                        .withSize(size.width, size.height)
                        .withDestinationDir(output),
                )
        }
    var failure: Throwable? = null
    try {
        action(adapter)
    } catch (caught: Throwable) {
        failure = caught
        throw caught
    } finally {
        try {
            adapter.configureViewport(IntSize(previous.first, previous.second), previous.third)
        } catch (cleanup: Throwable) {
            val primary = failure
            if (primary == null) {
                throw cleanup
            } else if (primary !== cleanup) {
                primary.addSuppressed(cleanup)
            }
        }
    }
}

private fun IntOffset.canvasMouseEvent(): MouseButtonEvent = MouseButtonEvent(x.toDouble(), y.toDouble(), MouseButtonInfo(PRIMARY_MOUSE_BUTTON, NO_MODIFIERS))

private const val PRIMARY_MOUSE_BUTTON = 0
private const val NO_MODIFIERS = 0
