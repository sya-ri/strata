package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.ui.UiDefinition
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.ClientPackSource
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/**
 * Verifies ordinary screen opening with the built-in high contrast pack, restoring the original resource selection afterward.
 */
internal object MinecraftHighContrastGameTest {
    /**
     * Runs on the Fabric test thread and dispatches pack reloads and screen ownership to the client thread.
     */
    fun run(context: ClientGameTestContext) {
        val selected =
            context.computeOnClient(
                FailableFunction<Minecraft, List<String>, RuntimeException> { it.resourcePackRepository.selectedIds.toList() },
            )
        val output = Path.of(checkNotNull(System.getProperty("strata.minecraftParityOutput"))).resolve("high-contrast")
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("passed.txt"))
        try {
            reload(context, selected + ClientPackSource.HIGH_CONTRAST_PACK)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    check(ClientPackSource.HIGH_CONTRAST_PACK in minecraft.resourcePackRepository.selectedIds)
                    UiDefinition("High contrast regression") {
                        Column {
                            Button("Enabled")
                            Button("Disabled", enabled = false)
                            Slider("Slider", SliderState(0.5))
                        }
                    }.open()
                },
            )
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(2)
            context.takeScreenshot(TestScreenshotOptions.of("screen").disableCounterPrefix().withDestinationDir(output))
            Files.writeString(output.resolve("passed.txt"), "Opened and rendered a Strata screen with the built-in high contrast pack.\n")
        } finally {
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    MinecraftClientScreenAccess.currentScreen(minecraft)?.onClose()
                    MinecraftClientScreenAccess.setScreen(minecraft, null)
                },
            )
            reload(context, selected)
        }
    }

    /**
     * Awaits the real resource reload and its loading overlay without blocking the client thread.
     */
    private fun reload(
        context: ClientGameTestContext,
        selected: List<String>,
    ) {
        val reloaded =
            context.computeOnClient(
                FailableFunction<Minecraft, CompletableFuture<Void>, RuntimeException> { minecraft ->
                    minecraft.resourcePackRepository.setSelected(selected)
                    minecraft.reloadResourcePacks()
                },
            )
        context.waitFor(Predicate<Minecraft> { reloaded.isDone })
        reloaded.join()
        context.waitFor(Predicate<Minecraft> { MinecraftClientScreenAccess.hasOverlay(it).not() })
    }
}
