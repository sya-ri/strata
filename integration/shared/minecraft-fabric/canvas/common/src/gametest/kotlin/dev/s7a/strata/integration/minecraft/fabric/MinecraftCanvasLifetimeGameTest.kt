package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureProvider
import dev.s7a.strata.runtime.minecraft.fabric.canvasSource
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Exercises real target resize, source replacement, resource reload, keyed churn, and empty native captures.
 *
 * The runner marshals every scene mutation to the client thread and waits on explicit state rather than fixed frame-age retirement.
 * Renderer probes check actual native last-use fences; target diagnostics include asynchronously retired physical storage.
 * Arbitrarily delayed synthetic fences are tested separately by the Minecraft-independent lifetime suite.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasLifetimeGameTest {
    // Why: native assertions must preserve arbitrary primary failures while independent fixture cleanup is still attempted.

    /**
     * Runs the lifecycle cases with real native resources and preserves primary failures through complete fixture cleanup.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 }
        context.configureViewport(IntSize(640, 480), 1)
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        val scene = context.onClient { MinecraftCanvasLifecycleScene(fixture.rendererSource, IntSize(32, 32)) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
            screen = owned
            context.onClient { context.setScreen(owned) }
            context.waitFor { fixture.renderersOpened == 1 && fixture.physicalSize == IntSize(32, 32) }
            val identity = context.onClient { checkNotNull(scene.lastCanvasId) }
            verifyResize(context, fixture, scene, identity)
            verifySourceReplacement(context, fixture, scene, identity)
            verifyResourceReload(context, fixture, scene, identity)
            verifyKeyChurn(context, fixture, scene)
            val path = context.takeScreenshot("strata-canvas-native-lifetime", IntSize(640, 480))
            val image = checkNotNull(ImageIO.read(path.toFile()))
            check(image.getRGB(16, 16) == 0xFF008000.toInt()) { "Native custom output changed after resize, source replacement, reload, or key churn." }
            check(image.getRGB(40, 16) == 0xFF000000.toInt()) { "Retired larger targets leaked outside the current Canvas extent." }
            verifyEmptyCapture(context, fixture, scene)
            Files.writeString(
                path.resolveSibling("strata-canvas-native-lifetime.txt"),
                "case=strata-canvas-native-lifetime\nphysical=640x480\nguiScale=1\nresizeCases=12\nsourceExchanges=2\nresourceReloads=1\nkeyChanges=12\n" +
                    "rendererFenceProbes=real-native\nemptyCaptureRetirement=complete\nretainedTargetSets=0\nbackend=${fixture.backendDescription}\n",
            )
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { awaitRetirement(context, fixture) },
                { context.onClient { fixture.close() } },
            )
        }
    }

    private fun verifyResize(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scene: MinecraftCanvasLifecycleScene,
        identity: CanvasId,
    ) {
        val sizes = listOf(IntSize(31, 29), IntSize(47, 17), IntSize(63, 41), IntSize(32, 32))
        repeat(3) {
            sizes.forEach { size ->
                context.onClient { scene.resize(size) }
                context.waitFor { fixture.logicalSize == size && fixture.physicalSize == size }
                context.onClient {
                    check(scene.lastCanvasId == identity) { "Resizing must retain the Canvas node's scalar identity." }
                    check(fixture.renderersOpened == 1) { "Resizing must not reconstruct the attachment renderer." }
                    check(NativeCanvasDevices.retainedTargetCount() <= 3) { "One Canvas exceeded its physical three-target bound during resize." }
                }
            }
        }
    }

    private fun verifySourceReplacement(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scene: MinecraftCanvasLifecycleScene,
        identity: CanvasId,
    ) {
        context.onClient { scene.replaceSource(fixture.textureSource) }
        context.waitFor { 0 < fixture.leasesOpened && fixture.renderersClosed == 1 }
        context.onClient {
            check(scene.lastCanvasId == identity) { "Source replacement must retain the existing Canvas identity." }
            check(NativeCanvasDevices.retainedTargetCount() <= 3)
            scene.replaceSource(fixture.rendererSource)
        }
        context.waitFor { fixture.renderersOpened == 2 && fixture.leasesOpened == fixture.leasesClosed }
        context.onClient { check(scene.lastCanvasId == identity) }
    }

    private fun verifyResourceReload(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scene: MinecraftCanvasLifecycleScene,
        identity: CanvasId,
    ) {
        val reloaded = context.onClient { Minecraft.getInstance().reloadResourcePacks() }
        context.waitFor { reloaded.isDone }
        context.onClient {
            reloaded.join()
            Unit
        }
        context.waitFor { context.hasOverlay().not() }
        context.waitFor { fixture.renderersOpened == 3 && fixture.renderersClosed == 2 }
        context.onClient { check(scene.lastCanvasId == identity) { "Resource reload must retain the Canvas node identity." } }
    }

    private fun verifyKeyChurn(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scene: MinecraftCanvasLifecycleScene,
    ) {
        repeat(12) { index ->
            val previous = context.onClient { checkNotNull(scene.lastCanvasId) }
            context.onClient { scene.replaceKey() }
            context.waitFor { fixture.renderersOpened == index + 4 }
            context.onClient {
                check(scene.lastCanvasId != previous) { "Key replacement must open a new Canvas identity." }
                check(NativeCanvasDevices.retainedTargetCount() <= 64) { "Key churn exceeded the device's physical target bound." }
            }
        }
        context.waitFor { fixture.renderersClosed == fixture.renderersOpened - 1 }
    }

    private fun verifyEmptyCapture(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scene: MinecraftCanvasLifecycleScene,
    ) {
        context.onClient { scene.hide() }
        awaitRetirement(context, fixture)
        var emptyCaptures = 0
        context.onClient {
            val empty =
                canvasSource(
                    MinecraftCanvasTextureProvider {
                        emptyCaptures++
                        null
                    },
                )
            scene.show(empty, IntSize(32, 32))
        }
        context.waitFor { 0 < emptyCaptures && 0 < NativeCanvasDevices.retainedTargetCount() }
        context.onClient { check(NativeCanvasDevices.retainedTargetCount() <= 3) }
        context.onClient { scene.hide() }
        awaitRetirement(context, fixture)
    }

    private fun awaitRetirement(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
    ) {
        context.waitFor {
            fixture.leasesOpened == fixture.leasesClosed && fixture.renderersOpened == fixture.renderersClosed &&
                NativeCanvasDevices.retainedTargetCount() == 0
        }
    }
}
