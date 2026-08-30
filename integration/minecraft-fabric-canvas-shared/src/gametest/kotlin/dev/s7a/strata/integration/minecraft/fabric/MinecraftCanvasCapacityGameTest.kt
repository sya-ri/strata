package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Fills the real device's 64 target permits and verifies transparent initial output and preserved snapshots under backpressure.
 *
 * Native screenshots are compared with literal source texels, not with a CPU fallback or another renderer implementation.
 * The client-thread diagnostic counts active and retired physical target sets, including Vulkan's deferred destruction queue.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasCapacityGameTest {
    // Why: native assertions must preserve arbitrary primary failures while independent fixture cleanup is still attempted.

    /**
     * Runs a 65-Canvas scene and proves all native target permits return only after physical retirement.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 }
        context.configureViewport(IntSize(640, 480), 1)
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned =
                context.onClient {
                    val definition =
                        ScreenDefinition("Native Canvas capacity acceptance") {
                            Stack(Modifier.Empty.background(ArgbColor(0xFF000000.toInt()))) {
                                Row {
                                    repeat(65) { index -> Canvas(fixture.textureSource, IntSize(4, 4), key = ElementKey(index)) }
                                }
                            }
                        }
                    createMinecraftScreen(definition, profile, parent = null)
                }
            screen = owned
            context.onClient { context.setScreen(owned) }
            context.waitFor { fixture.leasesOpened == 64 && NativeCanvasDevices.retainedTargetCount() == 64 }
            val path = context.takeScreenshot("strata-canvas-native-capacity", IntSize(640, 480))
            val image = checkNotNull(ImageIO.read(path.toFile()))
            repeat(64) { index ->
                check(image.getRGB(index * 4 + 1, 1) == 0xFFFF0000.toInt()) { "An admitted native Canvas did not display its independent source texel." }
            }
            check(image.getRGB(257, 1) == 0xFF000000.toInt()) { "The first uncommitted Canvas must remain transparent when all target permits are reserved." }
            context.onClient { fixture.snapshotMode = MinecraftCanvasSnapshotMode.Matching }
            context.waitTicks(3)
            context.onClient {
                check(fixture.leasesOpened == 64) { "A full device must skip producer updates without allocating beyond its physical bound." }
                check(NativeCanvasDevices.retainedTargetCount() == 64)
                check(runCatching { owned.captureCanvasFrame() }.exceptionOrNull() is IllegalStateException) {
                    "Backpressure must not attach a newer snapshot to previously committed native pixels."
                }
                context.setScreen(null)
            }
            context.waitFor { fixture.leasesOpened == fixture.leasesClosed && NativeCanvasDevices.retainedTargetCount() == 0 }
            Files.writeString(
                path.resolveSibling("strata-canvas-native-capacity.txt"),
                "case=strata-canvas-native-capacity\nphysical=640x480\nguiScale=1\nrequestedCanvases=65\npeakPhysicalTargetSets=64\n" +
                    "checkedTexels=65\nbackpressurePreservesSnapshot=verified\nretainedTargetSets=0\nbackend=${fixture.backendDescription}\n",
            )
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { fixture.leasesOpened == fixture.leasesClosed && NativeCanvasDevices.retainedTargetCount() == 0 } },
                { context.onClient { fixture.close() } },
            )
        }
    }
}
