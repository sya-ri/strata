@file:Suppress("DEPRECATION") // Loaded parity retains the compatibility screen entry point.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
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
 * Replacing a fully clipped unsupported sampled image must prepare a new display list while preserving both visible textures with no rasterization or upload.
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
        val hidden = context.onClient { mutableStateOf(createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0x80336699.toInt(), -1))) }
        val prepend = context.onClient { mutableStateOf(false) }
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
                                    if (prepend.value) {
                                        Spacer(Modifier.Empty.size(8, 16).background(ArgbColor(0xFFAA5522.toInt())))
                                        Canvas(fixture.textureSource, IntSize(16, 16))
                                    }
                                    Spacer(Modifier.Empty.size(16, 16).background(color.value))
                                    Canvas(fixture.textureSource, IntSize(16, 16))
                                    Spacer(Modifier.Empty.size(16, 16).imageBackground(pattern, ImageScale.Stretch))
                                    Canvas(invisibleSource(hidden.value), IntSize(16, 16))
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
            val after = verifyVisibleImageUpdate(context, owned, before) { color.value = ArgbColor(0xFF2255AA.toInt()) }
            verifyInvisibleImageUpdate(context, owned, after) {
                hidden.value = createDrawImage(IntSize(2, 2), intArrayOf(0, -1, -1, 0x4088CC22))
            }
            verifyShiftedLayers(context, owned, context.onClient { checkNotNull(observation(owned)) }) { prepend.value = it }
            verifyScreenshot(context, viewport, scale)
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

    private fun verifyVisibleImageUpdate(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        before: Observation,
        update: () -> Unit,
    ): Observation {
        context.onClient { update() }
        context.waitFor { observation(screen)?.textures?.firstOrNull() !== before.textures.first() }
        val after = context.onClient { checkNotNull(observation(screen)) }
        check(after.textures.size == 2 && after.textures[1] === before.textures[1]) { "Changing the first portable region replaced an unchanged patterned texture." }
        check(after.rasterizations == before.rasterizations + 1 && after.uploads == before.uploads + 1) {
            "Partial replacement must rasterize and upload exactly one changed region: before=$before, after=$after"
        }
        return after
    }

    private fun verifyShiftedLayers(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        before: Observation,
        update: (Boolean) -> Unit,
    ) {
        context.onClient { update(true) }
        context.waitFor { observation(screen)?.textures?.size == 3 }
        val inserted = context.onClient { checkNotNull(observation(screen)) }
        check(inserted.textures.last() === before.textures.last()) { "Prepending a portable run replaced the unchanged suffix texture." }
        check(inserted.rasterizations == before.rasterizations + 2 && inserted.uploads == before.uploads + 2) {
            "Prepending must upload only the two new runs: before=$before, after=$inserted"
        }
        context.onClient { update(false) }
        context.waitFor { observation(screen)?.textures?.size == 2 }
        val removed = context.onClient { checkNotNull(observation(screen)) }
        check(removed.textures.last() === before.textures.last()) { "Removing a portable run replaced the unchanged suffix texture." }
        check(removed.rasterizations == inserted.rasterizations + 1 && removed.uploads == inserted.uploads + 1) {
            "Removal must upload only the changed leading run: before=$inserted, after=$removed"
        }
    }

    private fun verifyInvisibleImageUpdate(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        before: Observation,
        update: () -> Unit,
    ) {
        context.onClient { update() }
        context.waitFor { observation(screen)?.let { before.preparations < it.preparations } == true }
        val after = context.onClient { checkNotNull(observation(screen)) }
        check(after.textures.size == before.textures.size && before.textures.indices.all { after.textures[it] === before.textures[it] }) {
            "Changing a fully clipped sampled image replaced a visible texture."
        }
        check(after.rasterizations == before.rasterizations && after.uploads == before.uploads) {
            "Invisible sampled inputs must not rasterize or upload: before=$before, after=$after"
        }
    }

    private fun verifyScreenshot(
        context: MinecraftCanvasTestContext,
        viewport: IntSize,
        scale: Int,
    ) {
        val path = context.takeScreenshot("strata-portable-layer-reuse-scale-$scale", viewport)
        val image = checkNotNull(ImageIO.read(path.toFile()))
        check(image.getRGB(scale, scale) == 0xFF2255AA.toInt()) { "The changed portable region retained stale pixels." }
        check(image.getRGB(33 * scale, scale) == 0xFF800000.toInt()) { "The retained translucent pattern changed its blend against the lower background." }
        check(image.getRGB(41 * scale, scale) == 0xFF00FF00.toInt()) { "The retained opaque pattern changed its texels." }
        check(image.getRGB(41 * scale, 9 * scale) == 0xFF000000.toInt()) { "Transparent retained texels obscured the lower background." }
        Files.writeString(path.resolveSibling("strata-portable-layer-reuse-scale-$scale.txt"), "guiScale=$scale\nportableLayers=2\nchangedRasterizations=1\nchangedUploads=1\ninvisibleImageRasterizations=0\ninvisibleImageUploads=0\nprependUploads=2\nremoveUploads=1\nshiftedTextureIdentity=preserved\nunchangedTextureIdentity=preserved\n")
    }

    private fun resourcesReleased(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0

    private fun invisibleSource(image: DrawImage): CanvasSource =
        CanvasSource {
            object : CanvasBinding {
                override fun paint(scope: PaintScope) {
                    scope.withClip(IntRect(0, 0, 0, 0)) {
                        scope.sampledImage(image, FloatRect(0.5f, 0f, 1.5f, 2f), FloatRect(0f, 0f, 16f, 16f), tint = ArgbColor(0x80FFFFFF.toInt()))
                    }
                }

                override fun close(): Unit = Unit
            }
        }

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
            read(owner, "framePreparationCount") as Long,
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
        val preparations: Long,
    )
}
