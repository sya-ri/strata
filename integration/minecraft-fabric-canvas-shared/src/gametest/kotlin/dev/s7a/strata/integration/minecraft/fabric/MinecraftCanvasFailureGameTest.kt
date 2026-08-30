package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
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
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/**
 * Fails a native renderer after real offscreen work and verifies atomic presentation and physical retirement.
 *
 * The first Canvas captures a real external texture, the second renders before throwing, and the third must never acquire a lease.
 * Manual native extraction stays inside one client task so the expected marker cannot reach Minecraft's outer crash handler.
 * Independent framebuffer copies and fresh deferred GUI-state traversal detect partial output without a CPU placeholder.
 * Fixture renderer resources carry their own real last-use fences, checked before their eventual close.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasFailureGameTest {
    /**
     * Verifies the exact producer failure, skipped tail capture, terminal screen behavior, and release through actual GPU fences.
     *
     * The runner owns artifacts and marshals all native work through [context]; [profile] is borrowed unchanged.
     * The externally owned fixture closes only after every lease, renderer, and physical target permit retires.
     * Assertion, rendering, screenshot, scheduling, and cleanup failures propagate, preserving an earlier failure during cleanup.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 }
        context.configureViewport(IntSize(640, 480), 1)
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        val outcome =
            runCatching {
                val proof = context.onClient { failAttachedScreen(context, profile, fixture) }
                context.waitFor { proof.before.isDone && proof.after.isDone && retired(fixture) }
                verifyFramebuffers(proof)
                context.waitTicks(3)
                context.onClient { verifyRetirement(fixture) }
                proof
            }
        runCanvasTestCleanup(
            outcome.exceptionOrNull(),
            { context.onClient { fixture.afterNativeRender = null } },
            { context.waitFor { retired(fixture) } },
            { context.onClient { fixture.close() } },
        )
        writeProof(context.outputDirectory, fixture.backendDescription, outcome.getOrThrow())
    }

    private fun failAttachedScreen(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        fixture: MinecraftCanvasTestFixture,
    ): Proof {
        val definition =
            ScreenDefinition("Native Canvas producer-failure acceptance") {
                Stack(Modifier.Empty.background(ArgbColor(0xFF112233.toInt()))) {
                    Row {
                        Canvas(fixture.textureSource, IntSize(32, 32))
                        Canvas(fixture.rendererSource, IntSize(32, 32))
                        Canvas(fixture.textureSource, IntSize(32, 32))
                    }
                }
            }
        val screen = createMinecraftScreen(definition, profile, parent = null)
        val outcome =
            runCatching {
                context.setScreen(screen)
                failAfterNativeWork(context, screen, fixture)
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.setScreen(null) }, screen::close)
        return outcome.getOrThrow()
    }

    private fun failAfterNativeWork(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        fixture: MinecraftCanvasTestFixture,
    ): Proof {
        val marker = IllegalStateException("Expected failure after real native Canvas renderer work")
        var peakTargetSets = 0
        fixture.afterNativeRender = {
            check(fixture.leasesOpened == 1) { "The first texture must be captured before the failing renderer, without reaching the tail Canvas." }
            check(fixture.renderCalls == 1 && fixture.renderersOpened == 1 && fixture.renderersClosed == 0)
            peakTargetSets = NativeCanvasDevices.retainedTargetCount()
            check(peakTargetSets == 2) { "The first presentation must allocate exactly one target for each producer it reached." }
            throw marker
        }
        var before: CompletableFuture<Path>? = null
        val failure =
            extractMinecraftCanvasScreen(screen) {
                before = captureMinecraftCanvasNativeFrame(context.outputDirectory.resolve("strata-canvas-native-producer-failure-before.png"))
            }
        val after = captureMinecraftCanvasNativeFrame(context.outputDirectory.resolve("strata-canvas-native-producer-failure-after.png"))
        check(failure === marker) { "The expected native renderer failure was replaced: $failure" }
        check(marker.suppressed.isEmpty()) { "Native failure cleanup or GUI atomicity checks added unexpected suppressed failures." }
        check(fixture.snapshotMode == MinecraftCanvasSnapshotMode.Missing) { "Native failure acceptance must not supply a CPU snapshot." }
        check(fixture.leasesOpened == 1 && fixture.renderCalls == 1) { "A later producer ran after the native renderer failed." }
        val remaining = NativeCanvasDevices.retainedTargetCount()
        check(remaining <= peakTargetSets && peakTargetSets <= 3 && peakTargetSets <= 64) {
            "The failed first presentation exceeded either physical target bound."
        }
        check(extractMinecraftCanvasScreen(screen, {}) is IllegalStateException) { "A terminal screen must reject later native extraction before invoking a producer." }
        requireNativeAttachmentRejected(screen)
        check(runCatching { screen.captureCanvasFrame() }.exceptionOrNull() is IllegalStateException) { "A failed presentation exposed a completed capture receipt." }
        check(fixture.leasesOpened == 1 && fixture.renderCalls == 1) { "A terminal screen reentered a native producer." }
        return Proof(checkNotNull(before), after, peakTargetSets)
    }

    private fun requireNativeAttachmentRejected(screen: Screen) {
        check(runCatching { screen.added() }.exceptionOrNull() is IllegalStateException) { "The failed screen was not terminally closed." }
    }

    private fun retired(fixture: MinecraftCanvasTestFixture): Boolean =
        fixture.leasesOpened == fixture.leasesClosed &&
            fixture.renderersOpened == fixture.renderersClosed &&
            NativeCanvasDevices.retainedTargetCount() == 0

    private fun verifyRetirement(fixture: MinecraftCanvasTestFixture) {
        check(fixture.leasesOpened == 1 && fixture.leasesClosed == 1) { "The captured external texture lease did not close exactly once." }
        check(fixture.renderersOpened == 1 && fixture.renderersClosed == 1) { "The failing native renderer did not close after its independent GPU fence." }
        check(fixture.renderCalls == 1) { "A terminal producer ran again while awaiting GPU retirement." }
        check(NativeCanvasDevices.retainedTargetCount() == 0) { "Failed native preparation retained a physical target permit." }
    }

    private fun verifyFramebuffers(proof: Proof) {
        val before = checkNotNull(ImageIO.read(proof.before.join().toFile()))
        val after = checkNotNull(ImageIO.read(proof.after.join().toFile()))
        check(before.width == 640 && before.height == 480 && after.width == before.width && after.height == before.height)
        val baseline = before.getRGB(0, 0, before.width, before.height, null, 0, before.width)
        val observed = after.getRGB(0, 0, after.width, after.height, null, 0, after.width)
        check(baseline.contentEquals(observed)) { "Failed Canvas preparation partially changed the actual native framebuffer." }
    }

    private fun writeProof(
        output: Path,
        backend: String,
        proof: Proof,
    ) {
        Files.writeString(
            output.resolve("strata-canvas-native-producer-failure.txt"),
            "case=strata-canvas-native-producer-failure\nphysical=640x480\nguiScale=1\n" +
                "exactPrimary=verified\nrealOffscreenWork=verified\ntailProducerSkipped=verified\nnoPartialGuiOutput=verified\n" +
                "checkedFramebufferPixels=307200\npeakPhysicalTargetSets=${proof.peakTargetSets}\n" +
                "leasesOpened=1\nleasesClosed=1\nrenderersOpened=1\nrenderersClosed=1\nrenderCalls=1\n" +
                "retainedTargetSets=0\nterminalProducerSuppression=verified\nbackend=$backend\n",
        )
    }

    private data class Proof(
        val before: CompletableFuture<Path>,
        val after: CompletableFuture<Path>,
        val peakTargetSets: Int,
    )
}
