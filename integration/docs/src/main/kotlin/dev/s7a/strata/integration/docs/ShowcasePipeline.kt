package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.nio.file.Path

/**
 * Runs typed catalog validation, source extraction, and fresh CPU rendering without starting Minecraft or a GPU.
 *
 * Only the server-backed inventory frame comes from explicit native evidence, whose image and current source hashes are verified before rendering.
 * No source synchronization occurs until this pipeline returns detached images and a deterministic input receipt.
 */
internal object ShowcasePipeline {
    /**
     * Preflights sources and explicit inputs, then renders each portable scenario from the selected asset snapshot.
     *
     * @param launch validated repository, staging, read-only asset/evidence inputs, and component class paths.
     * @return verified output ready for the synchronizer.
     * @throws IllegalArgumentException when catalog, inventory, source, receipt, image, or metadata validation fails.
     * @throws ArithmeticException when a physical viewport extent cannot be represented.
     * @throws IllegalStateException when Minecraft component class loading or reflection fails.
     * @throws java.io.IOException when source or evidence reading fails.
     */
    internal fun prepare(launch: ShowcaseLaunchArguments): ShowcaseOutput {
        val normalizedProject = launch.projectRoot
        val normalizedStaging = launch.stagingRoot
        ShowcaseScenarioCatalog.validate()
        val discovered = ShowcaseInventory.discover(launch.componentClassDirectories)
        val expected = DocumentedComponent.entries.toSet()
        require(discovered == expected) {
            "Minecraft component inventory mismatch. Expected $expected but found $discovered."
        }
        val overviewScenario = ShowcaseScenarioCatalog.overview
        val overviewRegion = ShowcaseSources.extract(overviewScenario.source, normalizedProject)
        val componentRegions = ShowcaseScenarioCatalog.components.associate { scenario -> scenario.component to ShowcaseSources.extract(scenario.source, normalizedProject) }
        val screenRegions = ShowcaseScenarioCatalog.screens.associate { scenario -> scenario.screen to ShowcaseSources.extract(scenario.source, normalizedProject) }
        val inputs = launch.inputs
        val inventory =
            ShowcaseInventoryEvidence.load(inputs.nativeInventoryPng, inputs.nativeInventoryReceipt, screenRegions.getValue(DocumentedScreen.SynchronizedInventory).source)
        val assets =
            ShowcaseMinecraftAssets(
                inputs.clientJar,
                inputs.assetIndex,
                inputs.assetObjects,
                inputs.versionManifest,
                normalizedProject.resolve("integration/minecraft-fabric-unobfuscated/src/gametest/resources"),
            )
        val frames = LinkedHashMap<String, ShowcaseFrameReceipt>()
        val overview = renderOverview(overviewScenario, overviewRegion, assets, frames)
        val sections =
            ShowcaseScenarioCatalog.components.map { scenario ->
                renderComponent(scenario, componentRegions.getValue(scenario.component), assets, frames)
            }
        val screens =
            ShowcaseScenarioCatalog.screens.map { scenario ->
                renderScreen(scenario, screenRegions.getValue(scenario.screen), assets, inventory, frames)
            }
        return ShowcaseOutput(overview, sections, screens, normalizedStaging, ShowcaseHeadlessReceipt.create(assets.inputHashes(), frames, inventory.proofSha256))
    }

    /**
     * Serializes a prepared output into its unique staging directory.
     *
     * @param output fully preflighted rendered output.
     */
    internal fun writeStaging(output: ShowcaseOutput) {
        ShowcaseStorage.writeStaging(output)
    }

    /**
     * Verifies source documentation against a prepared output without modifying source files.
     *
     * @param projectRoot repository root containing checked documentation.
     * @param output freshly rendered expected output.
     */
    internal fun checkSource(
        projectRoot: Path,
        output: ShowcaseOutput,
    ) {
        ShowcaseStorage.checkSource(projectRoot, output)
    }

    private fun renderOverview(
        scenario: OverviewScenario,
        region: SourceRegion,
        assets: ShowcaseMinecraftAssets,
        frames: MutableMap<String, ShowcaseFrameReceipt>,
    ): ShowcaseOutput.Overview {
        require(scenario.viewport.width == 320 && scenario.viewport.height == 180 && scenario.scale == 1) {
            "Overview frame metadata differs from the headless rendering contract."
        }
        val png = ShowcaseHeadlessRenderer.overview(assets)
        frames["overview"] = ShowcaseFrameReceipt(ShowcaseViewport(scenario.viewport, scenario.scale), region.source, png)
        return ShowcaseOutput.Overview(region.source, ShowcaseMarkdown.forest(scenario.trees), png)
    }

    private fun renderComponent(
        scenario: ComponentScenario,
        region: SourceRegion,
        assets: ShowcaseMinecraftAssets,
        frames: MutableMap<String, ShowcaseFrameReceipt>,
    ): ShowcaseOutput.Section {
        val png = ShowcaseHeadlessRenderer.component(scenario, assets)
        frames["component.${scenario.component.slug}"] = ShowcaseFrameReceipt(scenario.viewportMetadata, region.source, png)
        return ShowcaseOutput.Section(
            scenario.component,
            ShowcaseMarkdown.section(scenario, region.source),
            png,
        )
    }

    /**
     * Verifies that catalog metadata describes the complete dedicated frame recorded by the loaded GameTest.
     *
     * @param scenario component catalog entry whose viewport drives generated documentation.
     * @param evidence receipt-backed component evidence loaded before staging.
     * @throws IllegalArgumentException when the logical viewport or pixel scale differs from the receipt contract.
     */
    internal fun verifyComponentViewport(
        scenario: ComponentScenario,
        evidence: ShowcaseParityEvidence,
    ) {
        val recordedViewport = evidence.componentViewport(scenario.component)
        require(scenario.viewport == recordedViewport.size && scenario.scale == recordedViewport.scale) {
            "${scenario.component.apiMethodName} full-frame metadata differs from the parity receipt: catalog=${scenario.viewport} at scale ${scenario.scale}, receipt=${recordedViewport.size} at scale ${recordedViewport.scale}."
        }
    }

    private fun renderScreen(
        scenario: ScreenScenario,
        region: SourceRegion,
        assets: ShowcaseMinecraftAssets,
        inventory: ShowcaseInventoryEvidence,
        frames: MutableMap<String, ShowcaseFrameReceipt>,
    ): ShowcaseOutput.Screen {
        require(scenario.scale == 1) {
            "${scenario.screen.title} frame metadata differs from the headless rendering contract."
        }
        val png: ByteArray
        val frame: ShowcaseFrameReceipt
        if (scenario.screen == DocumentedScreen.SynchronizedInventory) {
            png = inventory.png()
            frame = inventory.frame
            require(frame.viewport.size == IntSize(scenario.viewportWidth, scenario.viewportHeight) && frame.viewport.scale == scenario.scale) {
                "Native inventory evidence differs from the screen catalog viewport."
            }
        } else {
            png = ShowcaseHeadlessRenderer.screen(scenario, assets)
            frame = ShowcaseFrameReceipt(ShowcaseViewport(IntSize(scenario.viewportWidth, scenario.viewportHeight), scenario.scale), region.source, png)
        }
        frames["screen.${scenario.screen.slug}"] = frame
        return ShowcaseOutput.Screen(
            scenario.screen,
            ShowcaseScreenMarkdown.section(scenario, region.source),
            png,
        )
    }
}
