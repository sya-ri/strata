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
        return ShowcaseOutput(overview, sections, normalizedStaging, evidence.receipt())
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
            "Overview crop metadata differs from the parity contract."
        }
        return ShowcaseOutput.Overview(region.source, ShowcaseMarkdown.forest(scenario.trees), evidence.overviewPng())
    }

    private fun renderComponent(
        scenario: ComponentScenario,
        region: SourceRegion,
        evidence: ShowcaseParityEvidence,
    ): ShowcaseOutput.Section {
        require(scenario.scale == 1) {
            "${scenario.component.apiMethodName} crop metadata differs from the parity contract."
        }
        return ShowcaseOutput.Section(
            scenario.component,
            ShowcaseMarkdown.section(scenario, region.source),
            evidence.componentPng(scenario.component),
        )
    }
}
