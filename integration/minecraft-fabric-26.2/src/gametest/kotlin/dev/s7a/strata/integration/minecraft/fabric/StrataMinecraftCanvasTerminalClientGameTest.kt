package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction

/**
 * Runs the explicitly selected acceptance scope before arming the 26.2 client shutdown proof.
 *
 * The wrapper remains on Fabric's test thread and delegates all native access through the client context.
 * Arming transfers only immutable receipt configuration; it does not install a screen or allocate a GPU resource.
 * The runner restores its starting viewport and lets the native surface settle before Fabric performs its final checks and stops Minecraft.
 * Full font, text, and shared acceptance remains the default; Canvas-only backend acceptance is identified in the invocation-bound receipt.
 * Failed tests never arm the close observer or produce a terminal success receipt.
 */
public class StrataMinecraftCanvasTerminalClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val startingViewport =
            context.computeOnClient(
                FailableFunction<Minecraft, Triple<IntSize, IntSize, Int>, RuntimeException> { minecraft ->
                    val window = minecraft.window
                    Triple(
                        IntSize(window.screenWidth, window.screenHeight),
                        IntSize(window.width, window.height),
                        minecraft.options.guiScale().get(),
                    )
                },
            )
        val scope = MinecraftCanvasSuiteScope.current()
        when (scope) {
            MinecraftCanvasSuiteScope.Full -> StrataMinecraftFontGameTest().runTest(context)
            MinecraftCanvasSuiteScope.CanvasOnly -> MinecraftCanvasBackendGameTest.run(context)
            MinecraftCanvasSuiteScope.TerminalOnly -> context.waitTicks(5)
        }
        resizeMinecraftCanvasTestWindow(context, startingViewport.first, startingViewport.second)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(startingViewport.third)
                minecraft.resizeGui()
            },
        )
        context.waitTicks(5)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> {
                MinecraftCanvasTerminalTestHooks.arm(scope)
            },
        )
    }
}
