@file:Suppress("DEPRECATION") // Loaded controls retain the compatibility screen entry point.

package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Observes actual retained-screen preflight, GUI consumption and cleanup rather than a JVM borrow model.
 * Native screenshots retain the full frame and are compared with the actual prepared ordered commands.
 * These correctness probes provide no native timing, upload improvement or performance evidence.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // One loaded control owns every screen and hook cleanup boundary.
internal object MinecraftSamplingCoveragePresenterGameTest {
    /**
     * Writes real presenter observations, or an explicit unsupported-target report, without guessing admission.
     * All screen access is client-thread confined and native fixtures close before this runner returns.
     * Archive/class-tree qualification remains separately required even after these assertions succeed.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        val result =
            runCatching {
                val report = provenance(fixture.backendDescription)
                if (context.onClient { exactSupported() }) {
                    val cases = JsonArray()
                    for (case in MinecraftSamplingCoverageScene.Case.entries) cases.add(verifyCase(context, profile, case))
                    report.add("cases", cases)
                    report.add("lifetime", verifyQueuedClose(context, profile))
                    report.addProperty("presenterAssertionsPassed", true)
                } else {
                    report.addProperty("presenterAssertionsPassed", false)
                    report.addProperty("reason", "The actual adapter/native extent preflight does not admit the required exact lookup; no loaded-control pass is claimed")
                }
                report
            }
        runCanvasTestCleanup(result.exceptionOrNull(), { context.onClient { fixture.close() } })
        PerformanceJson.writeNew(context.outputDirectory.resolve("sampling-coverage-presenter-controls.json"), result.getOrThrow())
    }

    @Suppress("LongMethod") // A receipt is emitted only after this actual screen generation and all its pixel checks succeed.
    private fun verifyCase(context: MinecraftCanvasTestContext, profile: MinecraftUiProfile, case: MinecraftSamplingCoverageScene.Case): JsonObject {
        val viewport = if (case == MinecraftSamplingCoverageScene.Case.ExactBudgetFallback) IntSize(4096, 4096) else IntSize(640, 480)
        context.configureViewport(viewport, 1)
        val scene = context.onClient { MinecraftSamplingCoverageScene(case, viewport) }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        val outcome =
            runCatching {
                context.onClient { context.setScreen(screen) }
                context.waitFor { MinecraftSamplingCoveragePresentation.snapshot(screen) != null }
                val before = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)) }
                check(before.viewport == viewport) { "The actual native control viewport did not match the declared input." }
                admission(case, before)
                val oldBytes = before.maps.map(MinecraftSamplingCoveragePresentation::metadata)
                val baseline = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
                context.onClient {
                    when (case) {
                        MinecraftSamplingCoverageScene.Case.CleanSameCommandIdentity -> Unit
                        MinecraftSamplingCoverageScene.Case.UnchangedMetadataEquivalentOutput -> scene.repaint()
                        else -> scene.changeList()
                    }
                }
                if (case != MinecraftSamplingCoverageScene.Case.CleanSameCommandIdentity) context.waitFor { MinecraftSamplingCoveragePresentation.snapshot(screen)?.inputs !== before.inputs }
                MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, baseline)
                val after = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)) }
                admission(case, after)
                val newMaps = after.maps.count { map -> before.maps.none { it === map } }
                val clean = case == MinecraftSamplingCoverageScene.Case.CleanSameCommandIdentity
                check((before.inputs === after.inputs) == clean)
                check((before.commandIdentity === after.commandIdentity) == clean)
                check(after.preparationCount == before.preparationCount + if (clean) 0L else 1L)
                check(before.commands == after.commands)
                if (case == MinecraftSamplingCoverageScene.Case.ChangedListSameCommands) check(before.commands.zip(after.commands).all { (a, b) -> a === b })
                if (case == MinecraftSamplingCoverageScene.Case.UnchangedMetadataEquivalentOutput) check(before.commands.zip(after.commands).any { (a, b) -> a !== b })
                check(newMaps == if (after.maps.isEmpty() || clean) 0 else 1)
                before.maps.forEachIndexed { index, map -> check(oldBytes[index].contentEquals(MinecraftSamplingCoveragePresentation.metadata(map))) }
                after.maps.forEachIndexed { index, map -> check(oldBytes[index].contentEquals(MinecraftSamplingCoveragePresentation.metadata(map))) }
                val screenshot = pixels(context, screen, viewport, case.name)
                val lifecycle = if (clean) verifyReattachmentAndFailure(context, screen, scene) else null
                JsonObject().apply {
                    addProperty("case", case.name)
                    addProperty("sourcePixelsSha256", context.onClient { scene.sourcePixelsSha256() })
                    addProperty("viewportWidth", viewport.width)
                    addProperty("viewportHeight", viewport.height)
                    addProperty("density", 1)
                    addProperty("actualNewMaps", newMaps)
                    addProperty("returnedMaps", after.maps.size)
                    addProperty("capacityFallback", after.capacity)
                    addProperty("sameCommandIdentity", before.commandIdentity === after.commandIdentity)
                    addProperty("samePreparedInputs", before.inputs === after.inputs)
                    addProperty("preparationDelta", after.preparationCount - before.preparationCount)
                    addProperty("commandValuesEqual", before.commands == after.commands)
                    addProperty("sameCommandElements", before.commands.zip(after.commands).all { (a, b) -> a === b })
                    addProperty("differentCommandElement", before.commands.zip(after.commands).any { (a, b) -> a !== b })
                    addProperty("oldMapsImmutable", true)
                    addProperty("metadataEqual", true)
                    addProperty("nativePixelsCompared", viewport.width.toLong() * viewport.height)
                    addProperty("screenshot", screenshot.fileName.toString())
                    addProperty("screenshotSha256", ArtifactIdentity.file(screenshot))
                    if (lifecycle != null) add("lifecycle", lifecycle)
                }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.onClient { context.setScreen(null) } }, { context.onClient { screen.close() } }, { context.waitFor { retired(screen) } })
        return outcome.getOrThrow()
    }

    private fun admission(
        case: MinecraftSamplingCoverageScene.Case,
        snapshot: MinecraftSamplingCoveragePresentation.Snapshot,
    ) {
        val exact = case == MinecraftSamplingCoverageScene.Case.CleanSameCommandIdentity || case == MinecraftSamplingCoverageScene.Case.ChangedListSameCommands || case == MinecraftSamplingCoverageScene.Case.UnchangedMetadataEquivalentOutput
        check(snapshot.maps.size == if (exact) 1 else 0)
        check(snapshot.capacity == if (case == MinecraftSamplingCoverageScene.Case.ExactBudgetFallback) 1L else 0L)
    }

    private fun verifyReattachmentAndFailure(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        scene: MinecraftSamplingCoverageScene,
    ): JsonObject {
        val before = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)) }
        val source = MinecraftSamplingCoveragePresentation.source(before.maps.single())
        context.onClient { scene.replaceSource() }
        context.waitFor {
            MinecraftSamplingCoveragePresentation
                .snapshot(screen)
                ?.maps
                ?.singleOrNull()
                ?.let { MinecraftSamplingCoveragePresentation.source(it) !== source } == true
        }
        val replacement = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)) }
        check(source !== MinecraftSamplingCoveragePresentation.source(replacement.maps.single()))
        check(source.copyArgb().contentEquals(MinecraftSamplingCoveragePresentation.source(replacement.maps.single()).copyArgb()))
        check(MinecraftSamplingCoveragePresentation.metadata(before.maps.single()).contentEquals(MinecraftSamplingCoveragePresentation.metadata(replacement.maps.single())))
        context.onClient { context.setScreen(null) }
        context.waitFor { retired(screen) }
        context.onClient {
            check(MinecraftSamplingCoveragePresentation.released(screen))
            context.setScreen(screen)
        }
        context.waitFor { MinecraftSamplingCoveragePresentation.snapshot(screen) != null }
        val reattached = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)) }
        check(reattached.inputs !== replacement.inputs && reattached.maps.single() !== replacement.maps.single())
        val marker = IllegalStateException("Expected sampled Canvas paint failure before native submission")
        context.onClient {
            scene.fail(marker)
            check(extractMinecraftCanvasScreen(screen, {}) === marker)
            check(MinecraftSamplingCoveragePresentation.released(screen))
            check(runCatching { screen.added() }.exceptionOrNull() is IllegalStateException)
            context.setScreen(null)
        }
        context.waitFor { retired(screen) }
        return JsonObject().apply {
            addProperty("sourceReplacement", true)
            addProperty("distinctEqualPixelSource", true)
            addProperty("detachReattach", true)
            addProperty("preparationFailure", true)
            addProperty("primaryFailurePreserved", true)
            addProperty("terminalScreenRejected", true)
            addProperty("zeroRetainedOwners", true)
            addProperty("zeroRetainedSources", true)
        }
    }

    private fun verifyQueuedClose(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ): JsonObject {
        context.configureViewport(IntSize(640, 480), 1)
        val scene = context.onClient { MinecraftSamplingCoverageScene(MinecraftSamplingCoverageScene.Case.CleanSameCommandIdentity, IntSize(640, 480)) }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var closedBeforeConsumer = false
        var consumed = false
        var retainedBefore = 0
        val outcome =
            runCatching {
                context.onClient {
                    MinecraftCanvasConsumerTestHooks.arm(
                        {
                            val snapshot = checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen))
                            check(snapshot.maps.size == 1)
                            val manager = MinecraftSamplingCoveragePresentation.manager(screen)
                            check(MinecraftSamplingCoveragePresentation.pendingNative(manager) == 1)
                            screen.close()
                            check(MinecraftSamplingCoveragePresentation.released(screen))
                            retainedBefore = MinecraftSamplingCoveragePresentation.pendingNative(manager)
                            check(retainedBefore == 1) { "Screen close freed the sampled source before actual GUI consumption." }
                            closedBeforeConsumer = true
                        },
                        {
                            context.setScreen(null)
                            consumed = true
                        },
                    )
                    context.setScreen(screen)
                }
                context.waitFor { closedBeforeConsumer && consumed }
                context.waitFor { retired(screen) }
                JsonObject().apply {
                    addProperty("actualGuiConsumerCompleted", consumed)
                    addProperty("sourceRetainedAfterCloseBeforeConsumer", retainedBefore)
                    addProperty("zeroRetainedOwners", true)
                    addProperty("zeroRetainedSources", true)
                }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.onClient { MinecraftCanvasConsumerTestHooks.reset() } }, { context.onClient { context.setScreen(null) } }, { context.onClient { screen.close() } }, { context.waitFor { retired(screen) } })
        return outcome.getOrThrow()
    }

    private fun pixels(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        viewport: IntSize,
        id: String,
    ): Path {
        val baseline = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
        MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, baseline)
        val commands = context.onClient { checkNotNull(MinecraftSamplingCoveragePresentation.snapshot(screen)).commands }
        val expected = rasterizeHeadless(commands, viewport, 1)
        val path = context.takeScreenshot("sampling-coverage-$id", viewport)
        val actual = checkNotNull(ImageIO.read(path.toFile()))
        check(actual.width == viewport.width && actual.height == viewport.height)
        for (y in 0 until viewport.height) for (x in 0 until viewport.width) check(actual.getRGB(x, y) == expected.argbAt(x, y)) { "Native sampled control $id changed pixel ($x,$y)" }
        return path
    }

    private fun retired(screen: FabricMinecraftScreen): Boolean {
        val manager = MinecraftSamplingCoveragePresentation.manager(screen)
        return MinecraftSamplingCoveragePresentation.retained(manager) == 0 && MinecraftSamplingCoveragePresentation.noSourcesOrOwners(manager)
    }

    private fun provenance(backend: String): JsonObject {
        val map = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingMap", false, javaClass.classLoader)
        val targets = mapOf("sampling-map" to map.name, "screen" to FabricMinecraftScreen::class.java.name)
        return JsonObject().apply {
            addProperty("scope", "Actual loaded screen preflight and native GUI-consumer correctness; no performance measurement")
            addProperty("backend", backend)
            addProperty("runtimeArchiveSha256", ArtifactIdentity.fullCodeSource(map))
            addProperty("samplingMapClassSha256", LoadedArtifactMetadata.captureClassHashes(listOf(map)).get(map.name).asString)
            add("actualLoadedRuntime", LoadedArtifactMetadata.capture(checkNotNull(map.classLoader), targets, targets.keys))
            add("probeClassHashes", LoadedArtifactMetadata.captureClassHashes(listOf(MinecraftSamplingCoveragePresenterGameTest::class.java, MinecraftSamplingCoverageScene::class.java, MinecraftSamplingCoveragePresentation::class.java)))
        }
    }

    @Suppress("StringLiteralComparison") // Exact reflected JVM method names are adapter-boundary identifiers, not domain states.
    private fun exactSupported(): Boolean {
        val helpers = listOf("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledTextureLimitsKt", "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTextureFactoryKt")
        for (name in helpers) {
            val helper =
                try {
                    Class.forName(name)
                } catch (_: ClassNotFoundException) {
                    continue
                }
            val method = helper.declaredMethods.singleOrNull { it.name.substringBefore('$') == "supportsFabricMinecraftExactSampling" }
            if (method != null) return method.invoke(null) as Boolean
        }
        error("The actual adapter has no exact-sampling admission preflight.")
    }
}
