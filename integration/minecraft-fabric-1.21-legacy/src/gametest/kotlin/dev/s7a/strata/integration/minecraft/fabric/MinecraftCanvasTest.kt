@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Path

/**
 * Runs the native Canvas fixture through the legacy loaded-client coordinator and restores the previous viewport.
 *
 * Sources, rendering, and cleanup stay on the client thread; the coordinator owns screenshot files and bounded waits.
 * Assertion and infrastructure failures remain primary when viewport restoration also fails.
 */
internal fun runMinecraftCanvasTest(
    context: MinecraftLoadedTestContext,
    profile: MinecraftUiProfile,
    output: Path,
) {
    withMinecraftCanvasContext(context, output) { adapter -> MinecraftCanvasGameTest.run(adapter, profile) }
}

/**
 * Verifies native Canvas and populated Slot overlap while the caller's server-seeded inventory is active.
 *
 * The runner restores the original viewport and GUI scale and retains no screen after the shared acceptance scene closes.
 * Inventory contents remain owned by the caller and are not mutated by this rendering check.
 *
 * @param context legacy loaded-client coordinator, borrowed for owner-thread scheduling and screenshots.
 * @param profile immutable profile extracted from the active native resource manager.
 * @param output contained build directory receiving newly rendered acceptance screenshots.
 * @param inventoryIndex the caller's populated player inventory slot.
 * @throws Throwable preserving any scene failure while independent viewport restoration is attempted.
 */
internal fun runMinecraftCanvasSlotTest(
    context: MinecraftLoadedTestContext,
    profile: MinecraftUiProfile,
    output: Path,
    inventoryIndex: Int,
) {
    withMinecraftCanvasContext(context, output) { adapter -> MinecraftCanvasSlotGameTest.run(adapter, profile, inventoryIndex) }
}

private fun withMinecraftCanvasContext(
    context: MinecraftLoadedTestContext,
    output: Path,
    action: (MinecraftCanvasTestContext) -> Unit,
) {
    val previous =
        context.computeOnClient { minecraft ->
            Triple(minecraft.window.screenWidth, minecraft.window.screenHeight, minecraft.options.guiScale().get())
        }
    val adapter =
        object : MinecraftCanvasTestContext {
            override val outputDirectory: Path = output

            override fun <T : Any> onClient(action: () -> T): T = context.computeOnClient { action() }

            override fun setScreen(screen: Screen?) {
                Minecraft.getInstance().setScreen(screen)
            }

            override fun hasOverlay(): Boolean = Minecraft.getInstance().overlay != null

            override fun pressPointer(
                screen: Screen,
                position: IntOffset,
            ): Boolean = pressMinecraftScreen(screen, position)

            override fun dragPointer(
                screen: Screen,
                position: IntOffset,
                delta: IntOffset,
            ): Boolean = dragMinecraftScreen(screen, position, delta)

            override fun releasePointer(
                screen: Screen,
                position: IntOffset,
            ): Boolean = releaseMinecraftScreen(screen, position)

            override fun setWindowFocused(focused: Boolean) {
                focusMinecraftWindow(focused)
            }

            override fun configureViewport(
                size: IntSize,
                guiScale: Int,
            ) {
                context.computeOnClient { minecraft ->
                    minecraft.window.setWindowed(size.width, size.height)
                    minecraft.options.guiScale().set(guiScale)
                    minecraft.resizeDisplay()
                }
            }

            override fun waitFor(condition: () -> Boolean) {
                context.waitFor { condition() }
            }

            override fun waitTicks(ticks: Int) {
                context.waitTicks(ticks)
            }

            override fun takeScreenshot(
                name: String,
                size: IntSize,
            ): Path = context.takeScreenshot(name, output, size)
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
