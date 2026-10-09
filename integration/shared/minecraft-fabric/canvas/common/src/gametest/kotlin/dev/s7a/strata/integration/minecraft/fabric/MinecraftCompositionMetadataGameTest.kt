package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import javax.imageio.ImageIO

/**
 * Checks actual native consumption of newly prepared and reused CPU metadata against original complete headless pixels.
 * All compiled cases run at low/high GUI density, with two ordinary replacements and terminal owner release per scene.
 * Diagnostic observations borrow the current inputs synchronously and never replace metadata or native storage.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCompositionMetadataGameTest {
    private val physical = IntSize(1920, 1080)

    /**
     * Verifies factory-source identity, immutable metadata reuse, ordinary fallback and exact whole-screen pixels.
     * The surrounding version adapter restores its initial viewport, and each scene recreates all screenshots and receipts.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in listOf(1, 4)) {
            context.configureViewport(physical, scale)
            MinecraftCompositionMetadataCorpus.Case.entries.forEach { verify(context, profile, scale, it) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod") // Complete native observation and independently attempted terminal cleanup share one scene owner.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
        case: MinecraftCompositionMetadataCorpus.Case,
    ) {
        context.waitFor { released() }
        val logical = IntSize(physical.width / scale, physical.height / scale)
        val scene = context.onClient { MinecraftCompositionMetadataScene(case, logical, 2) }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            var before = context.onClient { observe(screen) }
            if (case == MinecraftCompositionMetadataCorpus.Case.SmallSourceFallback) check(before.maps.isEmpty()) else check(before.maps.isNotEmpty())
            val rows = ArrayList<String>()
            repeat(3) { generation ->
                if (0 < generation) {
                    val completed = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
                    context.onClient { scene.update() }
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, completed)
                    if (case.stationary.not()) context.waitFor { before.preparations < observe(screen).preparations }
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
                }
                val after = context.onClient { observe(screen) }
                val reused = after.maps.count { map -> before.maps.any { it === map } }
                val newMaps = if (generation == 0) after.maps else after.maps.filter { map -> before.maps.none { it === map } }
                val written = context.onClient { newMaps.sumOf { MinecraftCompositionParityInputs.member(it, "axisEntriesWritten") as Long } }
                if (0 < generation) {
                    checkReplacement(case, before, after, reused)
                    when (case) {
                        MinecraftCompositionMetadataCorpus.Case.TintChangedLarge, MinecraftCompositionMetadataCorpus.Case.CutoffChangedLarge, MinecraftCompositionMetadataCorpus.Case.ReplacementLarge -> check(written == 0L)
                        MinecraftCompositionMetadataCorpus.Case.ScrollLarge -> check(0L < written)
                        else -> Unit
                    }
                }
                if (case == MinecraftCompositionMetadataCorpus.Case.RebuiltLarge || case == MinecraftCompositionMetadataCorpus.Case.RepeatedTintsLarge) check(after.factorImages == 1)
                compare(context, screen, logical, scale, case, generation)
                rows.add("generation=$generation maps=${after.maps.size} reusedMaps=$reused newMapAxisEntriesWritten=$written factorImages=${after.factorImages} sourceImages=${after.sources.size}")
                before = after
            }
            Files.writeString(context.outputDirectory.resolve("composition-metadata-${case.name}-$scale.properties"), rows.joinToString("\n", postfix = "\ntolerance=0\nresult=passed\n"))
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

    @Suppress("CyclomaticComplexMethod") // Every compiled case declares its expected actual identity/control invalidation in one exhaustive fixture visitor.
    private fun checkReplacement(
        case: MinecraftCompositionMetadataCorpus.Case,
        before: Observation,
        after: Observation,
        reused: Int,
    ) {
        if (case.stationary) {
            check(before.inputs === after.inputs && before.preparations == after.preparations)
        } else {
            check(before.inputs !== after.inputs && before.preparations < after.preparations)
        }
        when (case) {
            MinecraftCompositionMetadataCorpus.Case.RebuiltSmall, MinecraftCompositionMetadataCorpus.Case.RebuiltLarge,
            MinecraftCompositionMetadataCorpus.Case.RepeatedTintsLarge, MinecraftCompositionMetadataCorpus.Case.ReversedTintsLarge,
            MinecraftCompositionMetadataCorpus.Case.UniqueTintsLarge, MinecraftCompositionMetadataCorpus.Case.NestedClipsLarge,
            -> check(reused == after.maps.size)

            MinecraftCompositionMetadataCorpus.Case.OneDirtyLarge, MinecraftCompositionMetadataCorpus.Case.InsertLarge, MinecraftCompositionMetadataCorpus.Case.RemoveLarge -> check(0 < reused)

            MinecraftCompositionMetadataCorpus.Case.TintChangedLarge, MinecraftCompositionMetadataCorpus.Case.CutoffChangedLarge,
            MinecraftCompositionMetadataCorpus.Case.ReplacementLarge, MinecraftCompositionMetadataCorpus.Case.ScrollLarge,
            -> check(reused == 0)

            else -> Unit
        }
        if (case == MinecraftCompositionMetadataCorpus.Case.ReplacementLarge) check(before.sources.single() !== after.sources.single())
    }

    private fun compare(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        logical: IntSize,
        scale: Int,
        case: MinecraftCompositionMetadataCorpus.Case,
        generation: Int,
    ) {
        val expected = context.onClient { rasterizeHeadless(screen.captureCanvasFrame(), logical, scale).copyArgb() }
        val screenshot = context.takeScreenshot("composition-metadata-${case.name}-$scale-$generation", physical)
        val actual = checkNotNull(ImageIO.read(screenshot.toFile()))
        check(actual.width == physical.width && actual.height == physical.height)
        for (y in 0 until physical.height) {
            for (x in 0 until physical.width) check(actual.getRGB(x, y) == expected[y * physical.width + x]) { "Complete native metadata pixels differ at ($x, $y) in $screenshot" }
        }
    }

    private fun observe(screen: FabricMinecraftScreen): Observation {
        val holder = MinecraftCompositionParityInputs.presenter(screen)
        val inputs = MinecraftCompositionParityInputs.member(holder, "preparedInputs")
        val portable = MinecraftCompositionParityInputs.member(inputs, "portable") as List<*>
        val maps = portable.filterNotNull().filter(MinecraftCompositionParityInputs::composed).map { MinecraftCompositionParityInputs.member(it, "composition") }
        val factors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        maps.forEach { factors.add(MinecraftCompositionParityInputs.member(it, "factors")) }
        val requests = MinecraftCompositionParityInputs.member(inputs, "sampledRequests")
        val sources = MinecraftCompositionParityInputs.member(requests, "images") as List<*>
        return Observation(inputs, maps, sources, factors.size, MinecraftCompositionParityInputs.member(holder, "framePreparationCount") as Long)
    }

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceBytes() == 0L

    private class Observation(
        val inputs: Any,
        val maps: List<Any>,
        val sources: List<*>,
        val factorImages: Int,
        val preparations: Long,
    )
}
