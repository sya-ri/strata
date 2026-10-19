@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Checks loaded CycleButton callback, publication and presentation parity across every existing native adapter target.
 * These native screen callbacks establish adapter input delivery, not physical operating-system device input.
 * Each independent case owns fresh state, observers and a screen; completed frames fence exact full-viewport pixels.
 * CPU fixtures and operation metrics remain the separate unchanged complete-operation corpus.
 */
internal object MinecraftCycleButtonSelectionGameTest {
    /**
     * Runs all native cases through the existing loaded-client scheduling, viewport and artifact infrastructure.
     * The caller restores its original viewport; this test restores HUD visibility and releases every scene on failure.
     */
    @Suppress("TooGenericExceptionCaught") // HUD restoration preserves the exact native or assertion failure.
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.configureViewport(MinecraftCycleButtonSelectionScene.viewport, 1)
        val hidden = context.onClient { context.exchangeHudHidden(true) }
        var failure: Throwable? = null
        try {
            for (case in MinecraftCycleButtonSelectionCase.entries) verify(context, profile, case)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(failure, { context.onClient { context.exchangeHudHidden(hidden) } })
        }
    }

    @Suppress("TooGenericExceptionCaught") // Assertion, native and callback failures remain primary while independent owners are released.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        case: MinecraftCycleButtonSelectionCase,
    ) {
        context.movePointer(MinecraftCycleButtonSelectionScene.outside)
        val scene = context.onClient { MinecraftCycleButtonSelectionScene(case) }
        var failure: Throwable? = null
        var screen: FabricMinecraftScreen? = null
        try {
            val owned = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
            screen = owned
            context.onClient { context.setScreen(owned) }
            context.waitFor { context.currentScreen() === owned && context.hasOverlay().not() }
            completedFrame(context, owned)
            context.onClient {
                scene.afterAttachment()
                scene.operate(context, owned)
            }
            completedFrame(context, owned)
            context.onClient { scene.afterOperationFrame() }
            context.movePointer(context.onClient { scene.pointerPosition })
            completedFrame(context, owned)
            context.onClient { scene.verify(scene.nativeFrame(owned)) }
            val expected = context.onClient { scene.expectedPixels(profile) }
            verifyPixels(context, case, expected)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.onClient { scene.close() } },
            )
        }
    }

    private fun verifyPixels(
        context: MinecraftCanvasTestContext,
        case: MinecraftCycleButtonSelectionCase,
        expected: HeadlessImage,
    ) {
        val name = "cycle-selection-${case.name}"
        val native = context.takeScreenshot("$name-native", MinecraftCycleButtonSelectionScene.viewport)
        val expectedPath = context.outputDirectory.resolve("$name-headless.png")
        val png = expected.encodePng()
        Files.write(expectedPath, png)
        val image = checkNotNull(ImageIO.read(native.toFile()))
        check(image.width == expected.size.width && image.height == expected.size.height)
        for (y in 0 until expected.size.height) {
            for (x in 0 until expected.size.width) {
                check(image.getRGB(x, y) == expected.argbAt(x, y)) { "Native $case pixel differs at ($x, $y): $native" }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        Files.writeString(context.outputDirectory.resolve("$name.properties"), "case=${case.name}\ninput_scope=native_screen_callbacks\nexpected_selection=${case.expectedValue}\nheadless_png_sha256=$hash\ntolerance=0\nresult=passed\n")
    }

    private fun completedFrame(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
    ) {
        MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.completedFrames(context, screen))
        MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.completedFrames(context, screen))
    }
}
