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
 * Checks actual native consumption of independently published outputs and generation-local scratch reuse against original complete headless pixels.
 * All compiled cases run at low/high GUI density, with two ordinary replacements and terminal owner release per scene.
 * Diagnostic observations borrow the current inputs synchronously and never replace metadata or native storage.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCompositionTargetsGameTest {
    private val physical = IntSize(1920, 1080)

    /**
     * Verifies factory-source identity, immutable target independence, ordinary fallback and exact whole-screen pixels.
     * The surrounding version adapter restores its initial viewport, and each scene recreates all screenshots and receipts.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in listOf(1, 4)) {
            context.configureViewport(physical, scale)
            MinecraftCompositionTargetsCorpus.Case.entries.forEach { verify(context, profile, scale, it) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod") // Complete native observation and independently attempted terminal cleanup share one scene owner.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
        case: MinecraftCompositionTargetsCorpus.Case,
    ) {
        context.waitFor { released() }
        val logical = IntSize(physical.width / scale, physical.height / scale)
        val scene = context.onClient { MinecraftCompositionTargetsScene(case, logical, 2) }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            var before = context.onClient { observe(screen) }
            if (case == MinecraftCompositionTargetsCorpus.Case.SmallSourceFallback) check(before.maps.isEmpty()) else check(before.maps.isNotEmpty())
            val rows = ArrayList<String>()
            val oldFrame = context.onClient { screen.captureCanvasFrame() }
            val oldPixels = context.onClient { rasterizeHeadless(oldFrame, logical, scale).copyArgb() }
            repeat(3) { generation ->
                if (0 < generation) {
                    val completed = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
                    context.onClient { scene.update() }
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, completed)
                    if (case.stationary.not()) context.waitFor { before.preparations < observe(screen).preparations }
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
                }
                val after = context.onClient { observe(screen) }
                val reused = after.textures.count { texture -> before.textures.any { it === texture } }
                if (0 < generation && case.stationary) check(before.inputs === after.inputs && before.preparations == after.preparations)
                if (0 < generation && case.stationary.not()) check(before.inputs !== after.inputs && before.preparations < after.preparations)
                if (0 < generation && case == MinecraftCompositionTargetsCorpus.Case.OneDirtyLarge) check(0 < reused)
                if (case.small) check(after.scratchShapes == 0)
                check(after.intermediateViews.none { intermediate -> after.outputViews.any { it === intermediate } })
                compare(context, screen, logical, scale, case, generation)
                check(context.onClient { rasterizeHeadless(oldFrame, logical, scale).copyArgb().contentEquals(oldPixels) })
                rows.add("generation=$generation maps=" + after.maps.size + " reusedOutputOwners=$reused preparedPasses=" + after.passes + " initializedScratchShapes=" + after.scratchShapes + " roundedReservationBytes=" + after.reservedBytes)
                before = after
            }
            Files.writeString(context.outputDirectory.resolve("composition-targets-${case.name}-$scale.properties"), rows.joinToString("\n", postfix = "\ntolerance=0\nresult=passed\n"))
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
                        check(field.get(holder) == null) { "A closed screen retained current composition metadata." }
                    }
                },
                { context.waitFor { released() } },
            )
        }
    }

    private fun compare(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        logical: IntSize,
        scale: Int,
        case: MinecraftCompositionTargetsCorpus.Case,
        generation: Int,
    ) {
        val expected = context.onClient { rasterizeHeadless(screen.captureCanvasFrame(), logical, scale).copyArgb() }
        val screenshot = context.takeScreenshot("composition-targets-${case.name}-$scale-$generation", physical)
        val actual = checkNotNull(ImageIO.read(screenshot.toFile()))
        check(actual.width == physical.width && actual.height == physical.height)
        for (y in 0 until physical.height) {
            for (x in 0 until physical.width) check(actual.getRGB(x, y) == expected[y * physical.width + x]) { "Complete native metadata pixels differ at ($x, $y) in $screenshot" }
        }
    }

    private fun observe(screen: FabricMinecraftScreen): Observation {
        val holder = MinecraftCompositionParityInputs.presenter(screen)
        val inputs = MinecraftCompositionParityInputs.member(holder, "preparedInputs")
        val prepared = MinecraftCompositionParityInputs.member(MinecraftCompositionParityInputs.member(holder, "portableFrames"), "current")
        val portable = MinecraftCompositionParityInputs.member(prepared, "images") as List<*>
        val textures = (MinecraftCompositionParityInputs.member(prepared, "textures") as List<*>).filterNotNull()
        val maps = portable.filterNotNull().filter(MinecraftCompositionParityInputs::composed).map { MinecraftCompositionParityInputs.member(it, "composition") }
        val workspace = nullableMember(prepared, "workspace")
        val scratch = workspace?.let { MinecraftCompositionParityInputs.member(it, "scratch") as Map<*, *> }
        val intermediates = scratch?.values?.filterNotNull()?.map { MinecraftCompositionParityInputs.member(it, "borrowed") } ?: emptyList()
        val outputs = textures.map { MinecraftCompositionParityInputs.member(it, "borrowed") }
        val reservations = MinecraftCompositionParityInputs.member(prepared, "imageReservations") as List<*>
        val sharedReservation = workspace?.let { (MinecraftCompositionParityInputs.member(MinecraftCompositionParityInputs.member(it, "plan"), "reservations") as List<*>).first() as IntSize }
        val sizes = reservations.map { it as IntSize } + (sharedReservation?.let(::listOf) ?: emptyList())
        val bytes = sizes.sumOf { it.width.toLong() * it.height * 4L }
        val passes = maps.sumOf { (MinecraftCompositionParityInputs.member(it, "sources") as List<*>).size }
        return Observation(inputs, maps, textures, outputs, intermediates, scratch?.size ?: 0, passes, bytes, MinecraftCompositionParityInputs.member(holder, "framePreparationCount") as Long)
    }

    private fun nullableMember(
        owner: Any,
        name: String,
    ): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.trySetAccessible())
        return field.get(owner)
    }

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceBytes() == 0L

    @Suppress("LongParameterList") // One untimed current-generation observation keeps independently measured identities and reservation work together.
    private class Observation(
        val inputs: Any,
        val maps: List<Any>,
        val textures: List<Any>,
        val outputViews: List<Any>,
        val intermediateViews: List<Any>,
        val scratchShapes: Int,
        val passes: Int,
        val reservedBytes: Long,
        val preparations: Long,
    )
}
