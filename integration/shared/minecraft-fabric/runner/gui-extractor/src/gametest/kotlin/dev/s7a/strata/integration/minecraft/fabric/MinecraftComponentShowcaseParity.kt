package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.readRenderWork
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.renderWork
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.hasExactPixels
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireExactPixels
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireImageSize
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import kotlin.io.path.inputStream

/**
 * Owns component screenshot comparisons on the runner thread and schedules retained/native work on the client thread.
 * The caller owns the loaded client and output directory; each comparison releases its active Fabric screen on success or failure.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftComponentShowcaseParity(
    private val context: ClientGameTestContext,
    private val profile: MinecraftUiProfile,
    private val output: Path,
) {
    /**
     * Captures and verifies every component, then returns the frames used by the combined parity receipt.
     */
    internal fun run(): Map<ComponentShowcase, ComponentShowcaseFrame> {
        val assets =
            context.computeOnClient(
                FailableFunction<Minecraft, ComponentShowcaseAssets, RuntimeException> {
                    ComponentShowcaseAssets(
                        image =
                            ImageSource.Pixels(
                                loadMinecraftUiImage(
                                    ResourceId(
                                        "strata_test",
                                        "textures/gui/coal_generator.png",
                                    ),
                                ),
                            ),
                        playerSkin =
                            PlayerSkinSource.Pixels(
                                loadMinecraftUiImage(ResourceId("minecraft", "textures/entity/player/slim/efe.png")),
                            ),
                    )
                },
            )
        val imageDirectory = output.resolve("components")
        Files.createDirectories(imageDirectory)
        val result =
            runCatching {
                ComponentShowcase.entries.associateWith { showcase -> capture(showcase, assets, imageDirectory) }
            }
        runCanvasTestCleanup(result.exceptionOrNull(), { closeFabricScreen(context) })
        return result.getOrThrow()
    }

    private fun capture(
        showcase: ComponentShowcase,
        assets: ComponentShowcaseAssets,
        imageDirectory: Path,
    ): ComponentShowcaseFrame {
        closeFabricScreen(context)
        val physicalSize = showcase.physicalSize
        configureWindow(showcase)
        val frame = captureHeadless(showcase, assets, imageDirectory)

        val tiledNavigation = AtomicReference<PanZoomState?>()
        val tiledMarker = AtomicReference<TiledImageMarkerSource?>()
        context.setScreen {
            val definition =
                if (showcase === ComponentShowcase.TiledImage) {
                    val navigation = PanZoomState()
                    val marker = TiledImageMarkerSource(DoubleOffset(32.0, 24.0))
                    tiledNavigation.set(navigation)
                    tiledMarker.set(marker)
                    createTiledImageShowcaseScreenDefinition(navigation, marker)
                } else {
                    showcase.definition(assets)
                }
            createMinecraftScreen(
                definition,
                profile,
                parent = null,
            )
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.input.setCursorPos(showcase.pointer.x.toDouble() * showcase.scale, showcase.pointer.y.toDouble() * showcase.scale)
        context.waitTicks(2)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                check(minecraft.window.guiScale == showcase.scale) { "The component capture lost its actual GUI density." }
                check(minecraft.window.guiScaledWidth == showcase.viewport.width && minecraft.window.guiScaledHeight == showcase.viewport.height)
            },
        )
        val fabricPath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-component-${showcase.slug}-fabric")
                    .disableCounterPrefix()
                    .withSize(physicalSize.width, physicalSize.height)
                    .withDestinationDir(output),
            )
        compareFrame(showcase, assets, frame.image, fabricPath)
        if (showcase === ComponentShowcase.TiledImage) {
            verifyTiledImageTextureReuse(checkNotNull(tiledNavigation.get()), checkNotNull(tiledMarker.get()))
        }
        return frame
    }

    private fun captureHeadless(
        showcase: ComponentShowcase,
        assets: ComponentShowcaseAssets,
        imageDirectory: Path,
    ): ComponentShowcaseFrame {
        val headless =
            context.computeOnClient(
                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                    renderHeadless(
                        profile,
                        showcase.definition(assets),
                        showcase.viewport,
                        showcase.pointer,
                        frameTime = FrameTime(0L),
                        scale = showcase.scale,
                    )
                },
            )
        if (showcase === ComponentShowcase.Canvas) requireCanvasKnownPixels(headless)
        val png = headless.encodePng()
        Files.write(imageDirectory.resolve("${showcase.slug}.png"), png)

        return ComponentShowcaseFrame(headless, png)
    }

    private fun verifyTiledImageTextureReuse(
        navigation: PanZoomState,
        marker: TiledImageMarkerSource,
    ) {
        val beforeZoom = renderWork(context)
        require(beforeZoom.sources.isNotEmpty() && beforeZoom.rasterTextures.isNotEmpty()) { "Tiled-image reuse must observe real source entries and CPU texture owners." }
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { navigation.zoomTo(2.0) })
        context.waitFor(Predicate<Minecraft> { minecraft -> beforeZoom.inputs !== readRenderWork(minecraft).inputs })
        val afterZoom = renderWork(context)
        require(beforeZoom.sameSources(afterZoom)) { "Zoom must reuse actual tile source entries." }
        require(beforeZoom.sameRaster(afterZoom)) { "Zoom must reuse actual localized CPU texture owners." }
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> {
                val previous = navigation.metrics.center
                check(previous != navigation.panBy(DoubleOffset(4.0, 0.0)))
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> afterZoom.inputs !== readRenderWork(minecraft).inputs })
        val afterPan = renderWork(context)
        require(afterZoom.sameSources(afterPan)) { "Pan must reuse actual tile source entries." }
        require(afterZoom.sameRaster(afterPan)) { "Pan must reuse actual localized CPU texture owners." }
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { marker.publish(DoubleOffset(40.0, 24.0)) })
        context.waitFor(Predicate<Minecraft> { minecraft -> afterPan.inputs !== readRenderWork(minecraft).inputs })
        val afterMarker = renderWork(context)
        require(afterPan.sameSources(afterMarker)) { "Marker updates must reuse actual tile source entries." }
        require(afterPan.sameRaster(afterMarker)) { "Marker updates must reuse actual localized CPU texture owners." }
    }

    private fun requireCanvasKnownPixels(image: HeadlessImage) {
        val expected =
            intArrayOf(
                0xFF4CC9F0.toInt(),
                0xFF4361EE.toInt(),
                0xFF7209B7.toInt(),
                0xFFF72585.toInt(),
                0xFF90BE6D.toInt(),
                0xFFF9C74F.toInt(),
                0xFFF8961E.toInt(),
                0xFF7D2122.toInt(),
            )
        require(image.argbAt(0, 0) == 0xFF000000.toInt()) { "The Canvas showcase background differs from opaque black." }
        expected.indices.forEach { index ->
            val x = 24 + (index % 4) * 16
            val y = 24 + (index / 4) * 16
            if (image.argbAt(x, y) != expected[index]) {
                throw AssertionError("Canvas source texel $index differs from its independent expected straight-alpha color.")
            }
        }
    }

    private fun configureWindow(showcase: ComponentShowcase) {
        resizeMinecraftTestWindow(context, showcase.physicalSize)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(showcase.scale)
                minecraft.resizeGui()
                // Minimal component viewports are smaller than the options menu's 320 by 240 scale-selection floor.
                minecraft.window.setGuiScale(showcase.scale)
                check(minecraft.window.guiScaledWidth == showcase.viewport.width && minecraft.window.guiScaledHeight == showcase.viewport.height)
            },
        )
        context.input.setCursorPos(showcase.pointer.x.toDouble() * showcase.scale, showcase.pointer.y.toDouble() * showcase.scale)
    }

    private fun compareFrame(
        showcase: ComponentShowcase,
        assets: ComponentShowcaseAssets,
        headless: HeadlessImage,
        fabricPath: Path,
    ) {
        NativeImage.read(fabricPath.inputStream()).use { fabric ->
            requireImageSize(fabric, showcase.physicalSize)
            if (showcase.animationFrameTimes.isEmpty()) {
                requireExactPixels(fabric, headless)
            } else {
                val matched =
                    showcase.animationFrameTimes.any { frameTime ->
                        val candidate =
                            context.computeOnClient(
                                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                                    renderHeadless(
                                        profile,
                                        showcase.definition(assets),
                                        showcase.viewport,
                                        showcase.pointer,
                                        frameTime,
                                        showcase.scale,
                                    )
                                },
                            )
                        hasExactPixels(fabric, candidate)
                    }
                if (matched.not()) {
                    throw AssertionError(
                        "Fabric ${showcase.slug} pixels did not match any complete headless animation frame.",
                    )
                }
            }
        }
    }

    private class TiledImageMarkerSource(
        initial: DoubleOffset,
    ) : StateSource<DoubleOffset> {
        private val monitor = Any()
        private var snapshot = StateSnapshot(StateRevision(0L), initial)
        private val observers: MutableSet<(StateSnapshot<DoubleOffset>) -> Unit> = LinkedHashSet()

        override fun subscribe(observer: (StateSnapshot<DoubleOffset>) -> Unit): StateSubscription<DoubleOffset> {
            val initialSnapshot =
                synchronized(monitor) {
                    observers.add(observer)
                    snapshot
                }
            return StateSubscription(initialSnapshot) {
                synchronized(monitor) { observers.remove(observer) }
            }
        }

        fun publish(position: DoubleOffset) {
            val notification =
                synchronized(monitor) {
                    snapshot = StateSnapshot(StateRevision(Math.incrementExact(snapshot.revision.value)), position)
                    snapshot to observers.toList()
                }
            notification.second.forEach { observer -> observer(notification.first) }
        }
    }
}
