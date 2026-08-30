package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Checks real GPU texture sampling and independent custom offscreen rendering against literal native-screen texels.
 *
 * The runner thread owns screenshot files; every fixture, source, and screen operation is marshalled to the client thread.
 * The scene is tested at GUI scales one and two, then reopened to exercise new renderer attachments and resource retirement.
 * No headless output, supplied snapshots, or comparison between two Canvas implementations serves as the pixel oracle.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasGameTest {
    // Why: native assertions must preserve arbitrary primary failures while independent fixture cleanup is still attempted.

    /**
     * Runs native pixel, callback-scope, shared-source, resize, and external-ownership checks in the loaded client.
     *
     * The caller restores its previous viewport after return. Failure cleanup closes the screen before awaiting leases.
     * Native, assertion, scheduling, screenshot, and cleanup failures propagate to the owning loaded test.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            System.getProperty("strata.canvas.expectedBackend")?.let { expected ->
                check(fixture.backend == MinecraftCanvasTestBackend.parse(expected)) {
                    "The requested Canvas test backend $expected did not load: ${fixture.backendDescription}"
                }
            }
            context.configureViewport(viewport, 1)
            fixture.inputValidation.forEach { it.run(context, profile) }
            closeBeforeFirstPresentation(context, profile, fixture)
            val owned = context.onClient { createMinecraftScreen(createNativeCanvasScreenDefinition(fixture), profile, parent = null) }
            screen = owned
            context.onClient {
                // Keep the public adapter receiver so production remapping must resolve the inherited Minecraft callback through the runtime dependency.
                check(owned.isPauseScreen().not()) { "A native Canvas screen must preserve its non-pausing definition policy." }
                context.setScreen(owned)
            }
            verify(context, fixture, 1, "strata-canvas-native-scale-1")
            requireMissingSnapshot(context, owned)

            context.configureViewport(viewport, 2)
            verify(context, fixture, 2, "strata-canvas-native-scale-2")
            closeAndAwaitResources(context, fixture)
            context.onClient {
                check(fixture.renderersOpened == 3) { "Each of the three custom canvases must own one renderer." }
                check(0 < fixture.leasesOpened) { "The real texture provider must have captured native leases." }
            }

            context.onClient { context.setScreen(owned) }
            verify(context, fixture, 2, "strata-canvas-native-reattached")
            requireMissingSnapshot(context, owned)
            verifyExplicitCapture(context, owned, fixture)
            closeAndAwaitResources(context, fixture)
            context.onClient {
                check(fixture.renderersOpened == 6) { "Reopening must create fresh per-attachment renderer instances." }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { fixture.leasesOpened == fixture.leasesClosed && fixture.renderersOpened == fixture.renderersClosed } },
                { context.onClient { fixture.close() } },
            )
        }
        MinecraftCanvasLifetimeGameTest.run(context, profile)
        MinecraftCanvasCapacityGameTest.run(context, profile)
        MinecraftCanvasConsumptionGameTest.run(context, profile)
        MinecraftCanvasGenerationGameTest.run(context, profile)
        MinecraftCanvasPointerGameTest.run(context, profile)
        MinecraftCanvasFailureGameTest.run(context, profile)
    }

    private fun verify(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scale: Int,
        name: String,
    ) {
        context.waitFor {
            fixture.logicalSize == IntSize(32, 32) &&
                fixture.physicalSize == IntSize(32 * scale, 32 * scale) &&
                0 < fixture.renderCalls
        }
        context.waitTicks(2)
        val path = context.takeScreenshot(name, viewport)
        val image = ImageIO.read(path.toFile())
        checkNotNull(image) { "The loaded Canvas screenshot must be a readable native PNG." }
        check(image.width == viewport.width && image.height == viewport.height) { "The native screenshot must retain its full physical extent." }
        val samples =
            listOf(
                Triple(8, 8, 0xFFFF0000.toInt()),
                Triple(24, 8, 0xFF00FF00.toInt()),
                Triple(8, 24, 0xFF0000FF.toInt()),
                Triple(24, 24, 0xFFFFFF00.toInt()),
                Triple(56, 8, 0xFF0000FF.toInt()),
                Triple(72, 8, 0xFFFFFF00.toInt()),
                Triple(56, 24, 0xFFFF0000.toInt()),
                Triple(72, 24, 0xFF00FF00.toInt()),
                Triple(104, 8, 0xFFFF0000.toInt()),
                Triple(120, 24, 0xFFFFFF00.toInt()),
                Triple(148, 4, 0xFFFFFFFF.toInt()),
                Triple(168, 24, 0xFF00807F.toInt()),
                Triple(8, 56, 0xFFFF0000.toInt()),
                Triple(24, 56, 0xFF00FF00.toInt()),
                Triple(8, 68, 0xFF000000.toInt()),
                Triple(56, 56, 0xFF008000.toInt()),
                Triple(104, 56, 0xFFFFFFFF.toInt()),
                Triple(184, 88, 0xFF000000.toInt()),
            )
        for ((x, y, expected) in samples) {
            val actual = image.getRGB(x * scale, y * scale)
            check(actual == expected) {
                "Native Canvas texel ($x, $y) at scale $scale expected ${expected.toUInt().toString(16)}, found ${actual.toUInt().toString(16)}."
            }
        }
        if (scale == 2) {
            val physicalTexels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
            for (y in 0 until 2) {
                for (x in 0 until 2) {
                    check(image.getRGB(x, 192 + y) == physicalTexels[y * 2 + x]) {
                        "One logical Canvas pixel must preserve four distinct native physical texels at GUI scale two."
                    }
                }
            }
        } else {
            check(image.getRGB(0, 96) == 0xFFFFFF00.toInt()) { "Downsampling the 2 by 2 native source must select its pixel-center texel." }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        Files.writeString(
            path.resolveSibling("$name.txt"),
            "case=$name\nphysical=640x480\nguiScale=$scale\nsourceSnapshots=absent\ncheckedTexels=${samples.size + if (scale == 2) 4 else 1}\nsha256=$digest\n" +
                "backend=${fixture.backendDescription}\n",
        )
    }

    private fun closeAndAwaitResources(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
    ) {
        context.onClient { context.setScreen(null) }
        context.waitFor { fixture.leasesOpened == fixture.leasesClosed && fixture.renderersOpened == fixture.renderersClosed }
    }

    private fun requireMissingSnapshot(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            val failure = runCatching { screen.captureCanvasFrame() }.exceptionOrNull()
            check(failure is IllegalStateException) { "Native capture without snapshots must fail before returning any portable output." }
        }
    }

    // Why: cleanup must run after every native or assertion failure without replacing its original cause.
    @Suppress("TooGenericExceptionCaught")
    private fun closeBeforeFirstPresentation(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        fixture: MinecraftCanvasTestFixture,
    ) {
        context.onClient {
            val temporary = createMinecraftScreen(createNativeCanvasScreenDefinition(fixture), profile, parent = null)
            var failure: Throwable? = null
            try {
                context.setScreen(temporary)
                check(fixture.renderersOpened == 0 && fixture.leasesOpened == 0) {
                    "Attaching a Canvas must not initialize native producer resources before the first actual presentation."
                }
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                runCanvasTestCleanup(failure, { context.setScreen(null) }, temporary::close)
            }
            check(fixture.renderersClosed == 0 && fixture.leasesClosed == 0)
        }
    }

    private fun verifyExplicitCapture(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        fixture: MinecraftCanvasTestFixture,
    ) {
        context.onClient { fixture.snapshotMode = MinecraftCanvasSnapshotMode.Matching }
        context.waitFor { runCatching { screen.captureCanvasFrame() }.isSuccess }
        val commands = context.onClient { screen.captureCanvasFrame() }
        val headless = rasterizeHeadless(commands, IntSize(320, 240), scale = 2)
        val path = context.takeScreenshot("strata-canvas-native-snapshot", viewport)
        val native = checkNotNull(ImageIO.read(path.toFile())) { "The native snapshot comparison requires a readable full frame." }
        check(headless.size == viewport && native.width == viewport.width && native.height == viewport.height)
        for (y in 0 until viewport.height) {
            for (x in 0 until viewport.width) {
                check(native.getRGB(x, y) == headless.argbAt(x, y)) {
                    "The explicit same-generation Canvas snapshot differs from native rendering at physical texel ($x, $y)."
                }
            }
        }
        Files.write(path.resolveSibling("strata-canvas-captured-headless.png"), headless.encodePng())
        val retained = headless.copyArgb()
        closeAndAwaitResources(context, fixture)
        check(rasterizeHeadless(commands, IntSize(320, 240), scale = 2).copyArgb().contentEquals(retained)) {
            "An explicitly captured portable frame must stay immutable after native detach and resource retirement."
        }
        Files.writeString(
            path.resolveSibling("strata-canvas-native-snapshot.txt"),
            "case=strata-canvas-native-snapshot\nphysical=640x480\nguiScale=2\nsourceSnapshots=matching\ncheckedTexels=307200\n" +
                "backend=${fixture.backendDescription}\n",
        )
    }

    private val viewport = IntSize(640, 480)
}
