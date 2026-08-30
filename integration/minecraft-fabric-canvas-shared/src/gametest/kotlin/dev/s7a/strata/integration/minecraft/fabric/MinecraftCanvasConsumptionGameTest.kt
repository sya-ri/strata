package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/**
 * Closes a mixed portable/native screen at its first real GUI consumer and captures the completed same presentation.
 *
 * The clipped Canvas forces an intermediate flush in immediate GUI families, before the later portable overlay is submitted.
 * Deferred families close after extraction and before their queued GUI consumer.
 * Independent literal pixels prove both surrounding portable layers and the half-alpha native output survive close.
 * All callbacks belong to the client thread and are removed before cleanup even when a consumer fails.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasConsumptionGameTest {
    // Native assertions must preserve arbitrary primary failures while independent fixture cleanup is still attempted.

    /**
     * Verifies screen-reference release and queued resource retention through real consumption and physical destruction.
     *
     * Matching native snapshots make receipt assertions sensitive to accidental publication after reentrant close.
     * Only this explicit acceptance path reads the native framebuffer; production rendering performs no readback.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { allResourcesReleased() && context.hasOverlay().not() }
        context.configureViewport(IntSize(640, 480), 1)
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        val observation = Observation()
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned = install(context, profile, fixture, observation)
            screen = owned
            awaitConsumption(context, fixture, observation)
            verifyPixels(checkNotNull(observation.nativeCapture).join(), fixture.backendDescription)
            context.onClient { assertPresentationReleased(owned) }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                {
                    context.onClient {
                        MinecraftCanvasConsumerTestHooks.reset()
                        MinecraftCanvasCaptureTestHooks.reset()
                        fixture.afterNativeRender = null
                    }
                },
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { fixture.renderersOpened == fixture.renderersClosed && allResourcesReleased() } },
                { context.onClient { fixture.close() } },
            )
        }
    }

    private fun install(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        fixture: MinecraftCanvasTestFixture,
        observation: Observation,
    ): FabricMinecraftScreen =
        context.onClient {
            fixture.snapshotMode = MinecraftCanvasSnapshotMode.Matching
            val definition =
                ScreenDefinition("Mixed Canvas queued-close acceptance") {
                    Stack(Modifier.Empty.size(64, 48).background(ArgbColor(0xFF0000FF.toInt()))) {
                        canvasTestClip(IntSize(32, 32)) {
                            Canvas(fixture.rendererSource, IntSize(32, 32))
                        }
                        Spacer(Modifier.Empty.size(8, 8).background(ArgbColor(0xFFFF0000.toInt())))
                    }
                }
            val owned = createMinecraftScreen(definition, profile, parent = null)
            fixture.afterNativeRender = { arm(context, owned, fixture, observation) }
            context.setScreen(owned)
            owned
        }

    private fun arm(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        fixture: MinecraftCanvasTestFixture,
        observation: Observation,
    ) {
        if (observation.armed) return
        observation.armed = true
        MinecraftCanvasConsumerTestHooks.arm(
            {
                check(0 < NativeCanvasDevices.retainedTargetCount()) { "The native Canvas must have a queued target before close." }
                check(NativeCanvasDevices.retainedGuiResourceSetCount() == 1) { "Both portable layers must belong to one initialized GUI generation." }
                screen.close()
                observation.closedBeforeConsumer = true
                assertPresentationReleased(screen)
                assertCaptureRejected(screen)
                check(fixture.renderersClosed == 0) { "A queued renderer was released before the GUI consumer could use its target." }
                check(0 < NativeCanvasDevices.retainedTargetCount()) { "Closing the screen freed its still-queued target." }
                check(NativeCanvasDevices.retainedGuiResourceSetCount() == 1) { "Closing the screen freed portable layers before complete GUI consumption." }
                MinecraftCanvasCaptureTestHooks.arm {
                    check(observation.firstConsumerCompleted) { "The complete presentation capture preceded its first GUI consumer." }
                    assertPresentationReleased(screen)
                    assertCaptureRejected(screen)
                    val path = context.outputDirectory.resolve("strata-canvas-native-queued-close.png")
                    observation.nativeCapture = captureMinecraftCanvasNativeFrame(path)
                    context.setScreen(null)
                }
            },
            { observation.firstConsumerCompleted = true },
        )
    }

    private fun awaitConsumption(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        observation: Observation,
    ) {
        context.waitFor {
            observation.closedBeforeConsumer && observation.firstConsumerCompleted && observation.nativeCapture?.isDone == true
        }
        context.waitFor { fixture.renderersOpened == 1 && fixture.renderersClosed == 1 && allResourcesReleased() }
        context.onClient {
            check(fixture.renderCalls == 1) { "A closed Canvas producer ran after its queued presentation." }
        }
    }

    private fun verifyPixels(
        path: Path,
        backend: String,
    ) {
        val image = checkNotNull(ImageIO.read(path.toFile())) { "The mixed queued-close capture must produce a readable full-frame PNG." }
        check(image.width == 640 && image.height == 480)
        check(image.getRGB(1, 1) == 0xFFFF0000.toInt()) { "The portable foreground submitted after close did not survive GUI consumption." }
        check(image.getRGB(16, 16) == 0xFF00807F.toInt()) { "The half-alpha native Canvas did not composite over its retained portable blue background." }
        check(image.getRGB(48, 16) == 0xFF0000FF.toInt()) { "The queued portable background did not survive screen close." }
        Files.writeString(
            path.resolveSibling("strata-canvas-native-queued-close.txt"),
            "case=mixed-queued-close\nphysical=640x480\nguiScale=1\ncheckedTexels=3\ncloseBoundary=first-native-consumer\n" +
                "captureBoundary=complete-same-presentation\ntargetsAfter=0\nportableSetsAfter=0\nbackend=" + backend + "\n",
        )
    }

    private fun assertCaptureRejected(screen: FabricMinecraftScreen) {
        val failure = runCatching { screen.captureCanvasFrame() }.exceptionOrNull()
        check(failure is IllegalStateException) { "A closed screen retained or republished its matching-snapshot presentation receipt." }
    }

    private fun assertPresentationReleased(screen: FabricMinecraftScreen) {
        val fields = screen.javaClass.declaredFields.associateBy { it.name }
        val presenter = fields["presentation"]
        val owner =
            if (presenter == null) {
                screen
            } else {
                check(presenter.trySetAccessible()) { "The legacy presentation owner is inaccessible." }
                checkNotNull(presenter.get(screen))
            }
        val portable = checkNotNull(retained(owner, "portableFrames"))
        check(retained(portable, "current") == null) { "A closed screen retained or repopulated portable drawing and texture references." }
        check(retained(owner, "preparedCommands") == null) { "A closed screen retained or repopulated its display list." }
        check(retained(owner, "preparedViewport") == null) { "A closed screen retained or repopulated its prepared viewport." }
        check((retained(owner, "preparedLayers") as List<*>).isEmpty()) { "A closed screen retained or repopulated its prepared layers." }
        check(retained(owner, "pointerPosition") == null) { "A closed screen retained or repopulated its native pointer position." }
        check(retained(owner, "pointerFrameCommands") == null) { "A closed screen retained or repopulated its pointer display list." }
    }

    private fun retained(
        owner: Any,
        name: String,
    ): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.trySetAccessible()) { "The native presentation cache field is inaccessible: " + name }
        return field.get(owner)
    }

    private fun allResourcesReleased(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0

    private class Observation {
        var armed = false
        var closedBeforeConsumer = false
        var firstConsumerCompleted = false
        var nativeCapture: CompletableFuture<Path>? = null
    }
}
