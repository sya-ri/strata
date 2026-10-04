@file:Suppress("DEPRECATION") // Loaded parity retains the compatibility screen entry point.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Verifies partial portable replacement across a real native Canvas barrier at every GUI scale.
 *
 * An opaque changing region and a patterned translucent region use separate portable runs.
 * Texture identities and render counters prove reuse; literal screenshot texels independently verify updated and retained pixels.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftPortableLayerReuseGameTest {
    /**
     * Executes four loaded scenes and waits for physical resource retirement after each screen closes.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in 1..4) verify(context, profile, scale)
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup preserves native, assertion, and screenshot failures.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
    ) {
        context.waitFor { resourcesReleased() }
        val viewport = IntSize(1920, 1080)
        context.configureViewport(viewport, scale)
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        val color = context.onClient { mutableStateOf(ArgbColor(0xFF2277DD.toInt())) }
        val pattern = ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0)))
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned =
                context.onClient {
                    createMinecraftScreen(
                        ScreenDefinition("Portable layer reuse acceptance") {
                            Stack(Modifier.Empty.background(ArgbColor(0xFF000000.toInt()))) {
                                Row {
                                    Spacer(Modifier.Empty.size(16, 16).background(color.value))
                                    Canvas(fixture.textureSource, IntSize(16, 16))
                                    Spacer(Modifier.Empty.size(16, 16).imageBackground(pattern, ImageScale.Stretch))
                                }
                            }
                        },
                        profile,
                        parent = null,
                    )
                }
            screen = owned
            context.onClient { context.setScreen(owned) }
            context.waitFor { observation(owned)?.textures?.size == 2 }
            val before = context.onClient { checkNotNull(observation(owned)) }
            check(before.scale == scale) { "Portable reuse must observe the requested native GUI scale: requested=$scale, actual=${before.scale}" }
            context.onClient { color.value = ArgbColor(0xFF2255AA.toInt()) }
            context.waitFor { observation(owned)?.textures?.firstOrNull() !== before.textures.first() }
            val after = context.onClient { checkNotNull(observation(owned)) }
            check(after.textures.size == 2 && after.textures[1] === before.textures[1]) { "Changing the first portable region replaced an unchanged patterned texture." }
            check(after.rasterizations == before.rasterizations + 1 && after.uploads == before.uploads + 1) {
                "Partial replacement must rasterize and upload exactly one changed region: before=$before, after=$after"
            }
            val path = context.takeScreenshot("strata-portable-layer-reuse-scale-$scale", viewport)
            val image = checkNotNull(ImageIO.read(path.toFile()))
            check(image.getRGB(scale, scale) == 0xFF2255AA.toInt()) { "The changed portable region retained stale pixels." }
            check(image.getRGB(33 * scale, scale) == 0xFF800000.toInt()) { "The retained translucent pattern changed its blend against the lower background." }
            check(image.getRGB(41 * scale, scale) == 0xFF00FF00.toInt()) { "The retained opaque pattern changed its texels." }
            check(image.getRGB(41 * scale, 9 * scale) == 0xFF000000.toInt()) { "Transparent retained texels obscured the lower background." }
            Files.writeString(path.resolveSibling("strata-portable-layer-reuse-scale-$scale.txt"), "guiScale=$scale\nportableLayers=2\nchangedRasterizations=1\nchangedUploads=1\nunchangedTextureIdentity=preserved\n")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { resourcesReleased() && fixture.leasesOpened == fixture.leasesClosed } },
                { context.onClient { fixture.close() } },
            )
        }
    }

    private fun resourcesReleased(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0

    @Suppress("StringLiteralComparison") // Reflection selects a versioned adapter field by its JVM name, not a domain state.
    private fun observation(screen: FabricMinecraftScreen): Observation? {
        val presentation = screen.javaClass.declaredFields.singleOrNull { it.name == "presentation" }
        val owner = if (presentation == null) screen else checkNotNull(read(screen, "presentation"))
        val portable = checkNotNull(read(owner, "portableFrames"))
        val prepared = read(portable, "current") ?: return null
        return Observation(
            (read(prepared, "textures") as List<*>).map { checkNotNull(it) },
            read(owner, "portableRasterizationCount") as Long,
            read(owner, "textureUploadCount") as Long,
            read(checkNotNull((read(prepared, "images") as List<*>).first()), "scale") as Int,
        )
    }

    private fun read(
        owner: Any,
        name: String,
    ): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.trySetAccessible()) { "The portable presentation field is inaccessible: $name" }
        return field.get(owner)
    }

    private data class Observation(
        val textures: List<Any>,
        val rasterizations: Long,
        val uploads: Long,
        val scale: Int,
    )
}
