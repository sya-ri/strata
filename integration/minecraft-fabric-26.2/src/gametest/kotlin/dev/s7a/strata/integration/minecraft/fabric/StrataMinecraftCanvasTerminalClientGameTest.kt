package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer

/**
 * Runs the explicitly selected acceptance scope before arming the 26.2 client shutdown proof.
 *
 * The wrapper remains on Fabric's test thread and delegates all native access through the client context.
 * Arming transfers only immutable receipt configuration; it does not install a screen or allocate a GPU resource.
 * Fabric therefore still performs its normal final TitleScreen and disconnected-world checks before stopping Minecraft.
 * Full font, text, and shared acceptance remains the default; Canvas-only backend acceptance is identified in the invocation-bound receipt.
 * Failed tests never arm the close observer or produce a terminal success receipt.
 */
public class StrataMinecraftCanvasTerminalClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val scope = MinecraftCanvasSuiteScope.current()
        when (scope) {
            MinecraftCanvasSuiteScope.Full -> StrataMinecraftFontGameTest().runTest(context)
            MinecraftCanvasSuiteScope.CanvasOnly -> MinecraftCanvasBackendGameTest.run(context)
        }
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> {
                MinecraftCanvasTerminalTestHooks.arm(scope)
            },
        )
    }
}
