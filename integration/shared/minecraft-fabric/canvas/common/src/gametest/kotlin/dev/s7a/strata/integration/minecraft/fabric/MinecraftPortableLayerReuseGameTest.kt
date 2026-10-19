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
import dev.s7a.strata.modifier.fillMaxSize
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
 * Actual texture identities prove reuse; literal screenshot texels independently verify updated and retained pixels.
 * Replacing a fully clipped unsupported sampled image must prepare a new display list while preserving both visible texture owners.
 * A full-viewport background additionally proves one-tile replacement, bounded physical tile extents and native pixels after a small edit.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftPortableLayerReuseGameTest {
    /**
     * Executes both scenes at four GUI scales and waits for physical resource retirement after each screen closes.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in 1..4) {
            verify(context, profile, scale)
            verifyTiles(context, profile, scale)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup preserves native, assertion, and screenshot failures.
    private fun verifyTiles(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
    ) {
        context.waitFor { resourcesReleased() }
        context.configureViewport(IntSize(1920, 1080), scale)
        val color = context.onClient { mutableStateOf(ArgbColor(0xFF2277DD.toInt())) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned =
                context.onClient {
                    createMinecraftScreen(
                        ScreenDefinition("Portable tile reuse acceptance") {
                            Stack(Modifier.fillMaxSize().background(ArgbColor(0xFF123456.toInt()))) {
                                Spacer(Modifier.size(16, 16).background(color.value))
                            }
                        },
                        profile,
                        parent = null,
                    )
                }
            screen = owned
            context.onClient { context.setScreen(owned) }
            context.waitFor { observation(owned)?.textures?.size?.let { 1 < it } == true }
            val before = context.onClient { checkNotNull(observation(owned)) }
            check(before.textures.size <= 64 && before.scale == scale) { "Full-viewport fallback must use bounded tiles at the requested GUI scale." }
            context.onClient { color.value = ArgbColor(0xFF2255AA.toInt()) }
            context.waitFor { observation(owned)?.textures?.firstOrNull() !== before.textures.first() }
            val after = context.onClient { checkNotNull(observation(owned)) }
            check(
                after.textures.size == before.textures.size &&
                    after.textures
                        .drop(1)
                        .zip(before.textures.drop(1))
                        .all { (a, b) -> a === b },
            ) {
                "A small edit replaced unchanged full-viewport tiles: before=$before, after=$after"
            }
            val changedPixels = after.physicalSizes.first().let { Math.multiplyExact(it.width, it.height) }
            check(changedPixels in 1..65_536) { "A small edit replaced an oversized physical tile: $changedPixels pixels" }
            context.verifyTileScreenshot(scale, changedPixels, after.textures.size)
            val clean = context.onClient { checkNotNull(observation(owned)) }
            check(clean.inputs === after.inputs && clean.textures.size == after.textures.size && clean.textures.indices.all { clean.textures[it] === after.textures[it] }) {
                "Unchanged presented frames replaced prepared inputs or portable textures."
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { resourcesReleased() } },
            )
        }
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
                            Stack(Modifier.background(ArgbColor(0xFF000000.toInt()))) {
                                Row {
                                    if (prepend.value) {
                                        Spacer(Modifier.size(8, 16).background(ArgbColor(0xFFAA5522.toInt())))
                                        Canvas(fixture.textureSource, IntSize(16, 16))
                                    }
                                    Spacer(Modifier.size(16, 16).background(color.value))
                                    Canvas(fixture.textureSource, IntSize(16, 16))
                                    Spacer(Modifier.size(16, 16).imageBackground(pattern, ImageScale.Stretch))
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
            context.waitFor { observation(owned)?.textures?.size?.let { 2 <= it } == true }
            val before = context.onClient { checkNotNull(observation(owned)) }
            check(before.scale == scale) { "Portable reuse must observe the requested native GUI scale: requested=$scale, actual=${before.scale}" }
            val after = verifyVisibleImageUpdate(context, owned, before) { color.value = ArgbColor(0xFF2255AA.toInt()) }
            verifyInvisibleImageUpdate(context, owned, after) {
                hidden.value = createDrawImage(IntSize(2, 2), intArrayOf(0, -1, -1, 0x4088CC22))
            }
            verifyShiftedLayers(context, owned, context.onClient { checkNotNull(observation(owned)) }) { prepend.value = it }
            verifyScreenshot(context, viewport, scale, before.textures.size)
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
        check(
            after.textures.size == before.textures.size &&
                after.textures
                    .drop(1)
                    .zip(before.textures.drop(1))
                    .all { (a, b) -> a === b },
        ) { "Changing the first portable region replaced an unchanged texture." }
        return after
    }

    private fun verifyShiftedLayers(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        before: Observation,
        update: (Boolean) -> Unit,
    ) {
        context.onClient { update(true) }
        context.waitFor { observation(screen)?.textures?.size == before.textures.size + 1 }
        val inserted = context.onClient { checkNotNull(observation(screen)) }
        check(inserted.textures.last() === before.textures.last()) { "Prepending a portable run replaced the unchanged suffix texture." }
        check(inserted.textures.count { texture -> before.textures.none { it === texture } } == 2) {
            "Prepending must replace exactly the two changed portable runs."
        }
        context.onClient { update(false) }
        context.waitFor { observation(screen)?.textures?.size == before.textures.size }
        val removed = context.onClient { checkNotNull(observation(screen)) }
        check(removed.textures.last() === before.textures.last()) { "Removing a portable run replaced the unchanged suffix texture." }
        check(removed.textures.count { texture -> inserted.textures.none { it === texture } } == 1) {
            "Removal must replace only the changed leading portable run."
        }
    }

    private fun verifyInvisibleImageUpdate(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        before: Observation,
        update: () -> Unit,
    ) {
        context.onClient { update() }
        context.waitFor { observation(screen)?.let { before.inputs !== it.inputs } == true }
        val after = context.onClient { checkNotNull(observation(screen)) }
        check(after.textures.size == before.textures.size && before.textures.indices.all { after.textures[it] === before.textures[it] }) {
            "Changing a fully clipped sampled image replaced a visible texture."
        }
    }

    private fun verifyScreenshot(
        context: MinecraftCanvasTestContext,
        viewport: IntSize,
        scale: Int,
        portableLayers: Int,
    ) {
        val path = context.takeScreenshot("strata-portable-layer-reuse-scale-$scale", viewport)
        val image = checkNotNull(ImageIO.read(path.toFile()))
        check(image.getRGB(scale, scale) == 0xFF2255AA.toInt()) { "The changed portable region retained stale pixels." }
        check(image.getRGB(33 * scale, scale) == 0xFF800000.toInt()) { "The retained translucent pattern changed its blend against the lower background." }
        check(image.getRGB(41 * scale, scale) == 0xFF00FF00.toInt()) { "The retained opaque pattern changed its texels." }
        check(image.getRGB(41 * scale, 9 * scale) == 0xFF000000.toInt()) { "Transparent retained texels obscured the lower background." }
        Files.writeString(path.resolveSibling("strata-portable-layer-reuse-scale-$scale.txt"), "guiScale=$scale\nportableLayers=$portableLayers\nchangedTextureReplacements=1\ninvisibleImageTextureReplacements=0\nprependTextureReplacements=2\nremoveTextureReplacements=1\nrasterizations=unavailable\nuploads=unavailable\nshiftedTextureIdentity=preserved\nunchangedTextureIdentity=preserved\n")
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
            read(checkNotNull((read(prepared, "images") as List<*>).first()), "scale") as Int,
            checkNotNull(read(owner, "preparedInputs")),
            (read(prepared, "images") as List<*>).map { read(checkNotNull(it), "physicalSize") as IntSize },
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
        val scale: Int,
        val inputs: Any,
        val physicalSizes: List<IntSize>,
    )
}

private fun MinecraftCanvasTestContext.verifyTileScreenshot(
    scale: Int,
    changedPixels: Int,
    tiles: Int,
) {
    val path = takeScreenshot("strata-portable-tile-reuse-scale-$scale", IntSize(1920, 1080))
    val image = checkNotNull(ImageIO.read(path.toFile()))
    check(image.width == 1920 && image.height == 1080) { "Portable tile screenshot extent changed." }
    check(image.getRGB(8 * scale, 8 * scale) == 0xFF2255AA.toInt()) { "Changed tile pixels were not presented." }
    check(image.getRGB(600, 300) == 0xFF123456.toInt()) { "An unchanged tile lost its background pixels." }
    Files.writeString(path.resolveSibling("strata-portable-tile-reuse-scale-$scale.txt"), "guiScale=$scale\nportableTiles=$tiles\nchangedTextureReplacements=1\nchangedTilePhysicalPixels=$changedPixels\nrasterizations=unavailable\nuploads=unavailable\nuploadBytes=unavailable\nunchangedTextureIdentity=preserved\n")
}
