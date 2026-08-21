package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.nio.file.Path

/**
 * Runs typed showcase catalog validation, source extraction, and loaded-game evidence verification.
 *
 * No source synchronization occurs until this pipeline returns output backed by a verified receipt and detached image snapshots.
 */
internal object ShowcasePipeline {
    /**
     * Preflights every overview and component scenario against exact Minecraft parity evidence.
     *
     * @param launch validated repository, build, staging, and API class paths.
     * @return verified output ready for the synchronizer.
     * @throws IllegalArgumentException when catalog, inventory, source, receipt, image, or metadata validation fails.
     * @throws IllegalStateException when API class loading or reflection fails.
     * @throws java.io.IOException when source or evidence reading fails.
     */
    internal fun prepare(launch: ShowcaseLaunchArguments): ShowcaseOutput {
        val normalizedProject = launch.projectRoot
        val normalizedStaging = launch.stagingRoot
        ShowcaseScenarioCatalog.validate()
        val discovered = ShowcaseInventory.discover(launch.apiClassDirectories)
        val expected = DocumentedComponent.entries.toSet()
        require(discovered == expected) {
            "API component inventory mismatch. Expected $expected but found $discovered."
        }
        val sourceRoot = normalizedProject
        val evidence = ShowcaseParityEvidence.load(launch.parityRoot)
        val overviewScenario = ShowcaseScenarioCatalog.overview
        val overviewRegion = ShowcaseSources.extract(overviewScenario.source, sourceRoot)
        val overview = renderOverview(overviewScenario, overviewRegion, evidence)
        val pages =
            ShowcaseScenarioCatalog.components.map { scenario ->
                val region = ShowcaseSources.extract(scenario.source, sourceRoot)
                renderComponent(scenario, region, evidence)
            }
        return ShowcaseOutput(overview, pages, normalizedStaging, evidence.receipt())
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
        evidence: ShowcaseParityEvidence,
    ): ShowcaseOutput.Overview {
        require(scenario.viewport == expectedCropSize && scenario.scale == 1) { "Overview crop metadata differs from the parity contract." }
        return ShowcaseOutput.Overview(region.source, ShowcaseMarkdown.tree(scenario.tree), evidence.overviewPng())
    }

    private fun renderComponent(
        scenario: ComponentScenario,
        region: SourceRegion,
        evidence: ShowcaseParityEvidence,
    ): ShowcaseOutput.Page {
        require(scenario.viewport == expectedCropSize && scenario.scale == 1) {
            "${scenario.component.apiMethodName} crop metadata differs from the parity contract."
        }
        return ShowcaseOutput.Page(
            scenario.component,
            ShowcaseMarkdown.page(scenario, region.source),
            evidence.componentPng(scenario.component),
        )
    }

    private val expectedCropSize = IntSize(320, 180)
}
