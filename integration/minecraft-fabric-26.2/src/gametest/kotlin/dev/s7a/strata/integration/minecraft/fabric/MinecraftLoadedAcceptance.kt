package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Runs the explicitly selected acceptance scope before arming the 26.2 client shutdown proof.
 *
 * The wrapper remains on the standalone verification thread and delegates all native access through the client context.
 * Arming transfers only immutable receipt configuration; it does not install a screen or allocate a GPU resource.
 * The runner restores its starting viewport and lets the native surface settle before the standalone launcher stops Minecraft.
 * Full font, text, and shared acceptance remains the default; Canvas-only backend acceptance is identified in the invocation-bound receipt.
 * Failed tests never arm the close observer or produce a terminal success receipt.
 */
internal object MinecraftLoadedAcceptance {
    /**
     * Runs the selected scope and arms shutdown evidence only after all assertions pass.
     */
    fun run(context: MinecraftLoadedTestContext) {
        val startingViewport =
            context.computeOnClient(
                { minecraft ->
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
        PaperRemoteGameTest.run(context)
        resizeMinecraftTestWindow(context, startingViewport.first, startingViewport.second)
        context.runOnClient(
            { minecraft ->
                minecraft.options.guiScale().set(startingViewport.third)
                minecraft.resizeGui()
            },
        )
        context.waitTicks(5)
        context.runOnClient(
            {
                MinecraftCanvasTerminalTestHooks.arm(scope)
            },
        )
    }
}
