package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.activeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.readRenderWork
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableFunction
import java.util.function.Predicate
import dev.s7a.strata.integration.minecraft.fabric.MinecraftPresentationObservation.Snapshot as RenderWork

/**
 * Verifies per-host resource image identity and terminal presentation release on the loaded client.
 */
internal object MinecraftResourceImageMemoizationGameTest {
    /**
     * Checks shared image identity within each host and distinct decoded snapshots across independently closed hosts.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    internal fun run(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
    ) {
        closeFabricScreen(context)
        val first = captureResourceImageIdentity(context, profile)
        val second = captureResourceImageIdentity(context, profile)
        require(second !== first) {
            "The Fabric resource bridge did not decode a new image snapshot for the new host."
        }
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun captureResourceImageIdentity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
    ): DrawImage {
        val presentation =
            runCatching {
                context.setScreen {
                    createMinecraftScreen(
                        resourceImageMemoizationDefinition(),
                        profile,
                        parent = null,
                    )
                }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        val work = readRenderWork(minecraft)
                        0L < work.renderExtractions && work.sources.isNotEmpty() && work.portableTextures.isNotEmpty()
                    },
                )
                context.computeOnClient(
                    FailableFunction<Minecraft, DrawImage, RuntimeException> { minecraft ->
                        val screen = activeFabricScreen(minecraft)
                        requireResourceImageMemoizationWork(readRenderWork(minecraft))
                        resourceImageIdentity(screen)
                    },
                )
            }
        runCanvasTestCleanup(presentation.exceptionOrNull(), { closeFabricScreen(context) })
        return presentation.getOrThrow()
    }

    private fun requireResourceImageMemoizationWork(observed: RenderWork) {
        require(observed.sources.size == 1 && 0L < observed.sourceBytes) { "Equal resource Images must share one actual native source entry: $observed" }
        require(observed.portableTextures.isNotEmpty()) { "The ImageBackground must retain its real portable texture." }
    }

    private fun resourceImageIdentity(screen: FabricMinecraftScreen): DrawImage {
        val commandsField = FabricMinecraftScreen::class.java.getDeclaredField("preparedCommands")
        check(commandsField.trySetAccessible()) { "The Fabric prepared command cache is inaccessible." }
        val commands = commandsField.get(screen) as? List<*> ?: error("The Fabric screen has no prepared command list.")
        val sampled = commands.filterIsInstance<DrawCommand.SampledImage>()
        val backgrounds = commands.filterIsInstance<DrawCommand.BlitImage>()
        require(sampled.size == 2 && backgrounds.size == 1) {
            "The resource memoization scene must contain two deferred VirtualList Images and one ImageBackground."
        }
        val resolved = sampled.first().image
        require(sampled.all { command -> command.image === resolved } && backgrounds.all { command -> command.image === resolved }) {
            "Equal resource identifiers resolved to different image identities within one Minecraft host."
        }
        return resolved
    }

    private fun resourceImageMemoizationDefinition(): UiDefinition {
        val state = VirtualListState<Int>()
        val viewportSize = IntSize(resourceImageSize.width, resourceImageSize.height * 2)
        return UiDefinition("Resource image memoization") {
            Stack(
                modifier =
                    Modifier
                        .size(viewportSize.width, viewportSize.height)
                        .imageBackground(resourceImageSource(), ImageScale.Stretch),
            ) {
                VirtualList(listOf(0, 1), { item -> item }, state, viewportSize, rowHeight = resourceImageSize.height) {
                    Image(resourceImageSource(), resourceImageSourceRegion, resourceImageSize)
                }
            }
        }
    }

    private fun resourceImageSource(): ImageSource =
        ImageSource.Resource(
            ResourceId(
                "strata_test",
                "textures/gui/coal_generator.png",
            ),
        )

    private val resourceImageSourceRegion = IntRect(0, 0, 16, 16)
    private val resourceImageSize = IntSize(16, 16)
}
