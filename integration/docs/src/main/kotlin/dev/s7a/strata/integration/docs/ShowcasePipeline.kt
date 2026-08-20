package dev.s7a.strata.integration.docs

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessFrame
import java.nio.file.Path

/**
 * Runs typed showcase catalog validation, source extraction, topology checks, and rendering.
 *
 * No source synchronization occurs until this pipeline returns a fully rendered output.
 */
internal object ShowcasePipeline {
    /**
     * Preflights and renders every overview and component scenario.
     *
     * @param launch validated repository, build, staging, and API class paths.
     * @return fully rendered output ready for the synchronizer.
     * @throws IllegalArgumentException when catalog, inventory, source, topology, or metadata validation fails.
     * @throws IllegalStateException when API class loading or reflection fails.
     * @throws Throwable when scenario rendering or PNG encoding fails; the exact failure propagates.
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
        val sourceRoot = normalizedProject.resolve("integration/docs/src/main/kotlin").normalize()
        val overviewScenario = ShowcaseScenarioCatalog.overview
        val overviewRegion = ShowcaseSources.extract(overviewScenario.source, sourceRoot)
        val overview = renderOverview(overviewScenario, overviewRegion)
        val pages =
            ShowcaseScenarioCatalog.components.map { scenario ->
                val region = ShowcaseSources.extract(scenario.source, sourceRoot)
                renderComponent(scenario, region)
            }
        return ShowcaseOutput(overview, pages, normalizedStaging)
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
    ): ShowcaseOutput.Overview {
        val description = scenario.description()
        validateTreeArity(scenario.tree, description, "overview")
        val frame = scenario.render()
        validateFrame(frame, scenario.viewport, scenario.scale, "overview")
        return ShowcaseOutput.Overview(region.source, ShowcaseMarkdown.tree(scenario.tree), frame.image.encodePng())
    }

    private fun renderComponent(
        scenario: ComponentScenario,
        region: SourceRegion,
    ): ShowcaseOutput.Page {
        val description = scenario.description()
        validateTreeArity(scenario.tree, description, scenario.component.apiMethodName)
        val frame = scenario.render()
        validateFrame(frame, scenario.viewport, scenario.scale, scenario.component.apiMethodName)
        return ShowcaseOutput.Page(
            scenario.component,
            ShowcaseMarkdown.page(scenario, region.source),
            frame.image.encodePng(),
        )
    }

    private fun validateFrame(
        frame: HeadlessFrame,
        viewport: IntSize,
        scale: Int,
        label: String,
    ) {
        require(frame.viewport == viewport) { "$label frame viewport differs from catalog metadata." }
        require(frame.pixelScale == scale) { "$label frame scale differs from catalog metadata." }
        val width = Math.multiplyExact(viewport.width, scale)
        val height = Math.multiplyExact(viewport.height, scale)
        require(frame.image.size == IntSize(width, height)) {
            "$label frame image size differs from catalog metadata."
        }
        require(frame.semantics.isEmpty()) { "$label frame semantics must be empty." }
        val pixels = frame.image.copyArgb()
        require(pixels.isNotEmpty()) { "$label frame image must not be empty." }
        require(pixels.all { pixel -> pixel ushr 24 == 0xFF }) { "$label frame must be fully opaque." }
        require(2 <= pixels.toSet().size) { "$label frame must contain at least two colors." }
    }

    /**
     * Validates only direct-child arity against the returned public element tree.
     *
     * Modifier details remain authoritative typed catalog metadata because concrete element implementations are not inspected.
     */
    private fun validateTreeArity(
        expected: ShowcaseTree,
        actual: Element,
        path: String,
    ) {
        require(expected.children.size == actual.children.size) {
            "Showcase topology mismatch at $path: expected ${expected.children.size} children but found ${actual.children.size}."
        }
        expected.children.forEachIndexed { index, child ->
            validateTreeArity(child, actual.children[index], "$path/$index")
        }
    }
}
