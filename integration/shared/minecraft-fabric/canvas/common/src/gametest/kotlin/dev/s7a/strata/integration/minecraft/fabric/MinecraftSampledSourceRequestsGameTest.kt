package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Checks complete native pixels and current prepared identity requests on the real device, without timing thresholds.
 * Dense repeated draws, distinct equal-pixel identities and shared ordered composition run at low and high GUI density.
 * Every scene releases its current inputs and all native storage before the next scene is installed.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSampledSourceRequestsGameTest {
    private val physical = IntSize(1920, 1080)

    /**
     * Verifies original command pixels, clean request reuse, once-per-identity native hits and terminal screen release.
     * The caller restores its previous viewport; screenshots and receipts are fresh for this exact loaded revision.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val cases = listOf(MinecraftSampledSourceRequestsCorpus.Case.Requests4096Shared, MinecraftSampledSourceRequestsCorpus.Case.Requests64Unique, MinecraftSampledSourceRequestsCorpus.Case.RequestsComposedSixteen)
        for (scale in listOf(1, 4)) {
            context.configureViewport(physical, scale)
            cases.forEach { verify(context, profile, scale, it) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod") // One complete-frame assertion owns its capture and independently attempted terminal cleanup.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
        case: MinecraftSampledSourceRequestsCorpus.Case,
    ) {
        context.waitFor { released() }
        val logical = IntSize(physical.width / scale, physical.height / scale)
        val screen = context.onClient { createMinecraftScreen(MinecraftSampledSourceRequestsScene(case, logical, 1).definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            val before = context.onClient { observe(screen) }
            check(before.images == case.identities.toLong())
            check(before.uploads == case.identities.toLong())
            if (case.composed) check(context.onClient { MinecraftCompositionParityInputs.portable(screen).any { MinecraftCompositionParityInputs.composed(it.first) } })
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            val after = context.onClient { observe(screen) }
            check(before.inputs === after.inputs && before.requests === after.requests)
            check(before.preparations == after.preparations && before.uploads == after.uploads)
            check(after.hits - before.hits == (after.frames - before.frames) * case.identities)
            val expected = context.onClient { rasterizeHeadless(screen.captureCanvasFrame(), logical, scale).copyArgb() }
            val screenshot = context.takeScreenshot("source-requests-${case.name}-$scale", physical)
            val actual = checkNotNull(ImageIO.read(screenshot.toFile()))
            check(actual.width == physical.width && actual.height == physical.height)
            for (y in 0 until physical.height) {
                for (x in 0 until physical.width) check(actual.getRGB(x, y) == expected[y * physical.width + x]) { "Complete native source-request pixels differ at ($x, $y) in $screenshot" }
            }
            Files.writeString(context.outputDirectory.resolve("source-requests-${case.name}-$scale.properties"), "occurrences=${case.occurrences}\nidentities=${case.identities}\nsourceUploads=${after.uploads}\ntolerance=0\nresult=passed\n")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                {
                    context.onClient {
                        screen.close()
                        val holder = MinecraftCompositionParityInputs.presenter(screen)
                        val field = holder.javaClass.getDeclaredField("preparedInputs")
                        check(field.trySetAccessible())
                        check(field.get(holder) == null) { "A closed screen retained prepared source requests." }
                    }
                },
                { context.waitFor { released() } },
            )
        }
    }

    private fun observe(screen: FabricMinecraftScreen): Observation {
        val holder = MinecraftCompositionParityInputs.presenter(screen)
        val inputs = MinecraftCompositionParityInputs.member(holder, "preparedInputs")
        val requests = MinecraftCompositionParityInputs.member(inputs, "sampledRequests")
        val images = MinecraftCompositionParityInputs.member(requests, "images") as List<*>
        return Observation(inputs, requests, images.size.toLong(), counter(holder, "renderExtractionCount"), counter(holder, "sampledImageDirectHitCount"), counter(holder, "sampledImageUploadCount"), counter(holder, "framePreparationCount"))
    }

    private fun counter(
        holder: Any,
        name: String,
    ): Long = MinecraftCompositionParityInputs.member(holder, name) as Long

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceBytes() == 0L

    private data class Observation(
        val inputs: Any,
        val requests: Any,
        val images: Long,
        val frames: Long,
        val hits: Long,
        val uploads: Long,
        val preparations: Long,
    )
}
