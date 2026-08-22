package dev.s7a.strata.integration.docs

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
     * @param launch validated repository, build, staging, and Minecraft component class paths.
     * @return verified output ready for the synchronizer.
     * @throws IllegalArgumentException when catalog, inventory, source, receipt, image, or metadata validation fails.
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
        val evidence = ShowcaseParityEvidence.load(launch.parityRoot)
        val overviewScenario = ShowcaseScenarioCatalog.overview
        val overviewRegion = ShowcaseSources.extract(overviewScenario.source, normalizedProject)
        val overview = renderOverview(overviewScenario, overviewRegion, evidence)
        val sections =
            ShowcaseScenarioCatalog.components.map { scenario ->
                val region = ShowcaseSources.extract(scenario.source, normalizedProject)
                renderComponent(scenario, region, evidence)
            }
        val screens =
            ShowcaseScenarioCatalog.screens.map { scenario ->
                val region = ShowcaseSources.extract(scenario.source, normalizedProject)
                renderScreen(scenario, region, evidence)
            }
        return ShowcaseOutput(overview, sections, screens, normalizedStaging, evidence.receipt())
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
        require(scenario.viewport.width == 320 && scenario.viewport.height == 180 && scenario.scale == 1) {
            "Overview frame metadata differs from the parity contract."
        }
        return ShowcaseOutput.Overview(region.source, ShowcaseMarkdown.forest(scenario.trees), evidence.overviewPng())
    }

    private fun renderComponent(
        scenario: ComponentScenario,
        region: SourceRegion,
        evidence: ShowcaseParityEvidence,
    ): ShowcaseOutput.Section {
        verifyComponentViewport(scenario, evidence)
        return ShowcaseOutput.Section(
            scenario.component,
            ShowcaseMarkdown.section(scenario, region.source),
            evidence.componentPng(scenario.component),
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
        require(scenario.viewport == recordedViewport && scenario.scale == 1) {
            "${scenario.component.apiMethodName} full-frame metadata differs from the parity receipt: catalog=${scenario.viewport}, receipt=$recordedViewport, scale=${scenario.scale}."
        }
    }

    private fun renderScreen(
        scenario: ScreenScenario,
        region: SourceRegion,
        evidence: ShowcaseParityEvidence,
    ): ShowcaseOutput.Screen {
        require(scenario.scale == 1) {
            "${scenario.screen.title} frame metadata differs from the parity contract."
        }
        return ShowcaseOutput.Screen(
            scenario.screen,
            ShowcaseScreenMarkdown.section(scenario, region.source),
            evidence.screenPng(scenario.screen),
        )
    }
}
