package dev.s7a.strata.integration.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import dev.s7a.strata.quality.benchmark.ComponentWorkload
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.mountWeb
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.json

/**
 * Exhaustive Web admission of the same compiled standard declarations used by portable and native verification.
 * Unavailable declarations must fail explicitly; registration does not count their rejection as measured rendering.
 */
internal object WebComponentPerformanceContract {
    val viewport: IntSize = IntSize(320, 240)
    val phases: Set<PerformancePhase> = setOf(PerformancePhase.Initial, PerformancePhase.Idle, PerformancePhase.Resize, PerformancePhase.Release)

    /**
     * Produces build-time fixture data directly from the exhaustive compiled inventory.
     */
    fun inventory(): String {
        val unavailable =
            ComponentWorkload.entries
                .filter { available(it).not() }
                .map { it.name }
                .toTypedArray()
        return JSON.stringify(
            json(
                "supported" to supported().map { it.name }.toTypedArray(),
                "unavailable" to unavailable,
                "phases" to phases.map { it.name }.toTypedArray(),
            ),
        )
    }

    /**
     * Verifies every declaration against the actual themed host before performance sampling.
     */
    fun verify(theme: WebTheme) {
        val supported = supported()
        val features = supported.map { it.name }.toSet()
        PerformanceCoverage(
            features.associateWith { setOf(PerformanceHost.Web) },
            supported.map { PerformanceScenario(it.name, setOf(it.name), setOf(PerformanceHost.Web), phases, "standard-component-definitions-v1") },
            features.associateWith { phases },
        ).verify()
        for (component in ComponentWorkload.entries) {
            val root = document.createElement("div") as HTMLElement
            checkNotNull(document.body).appendChild(root)
            try {
                val result = runCatching { mountWeb(component.uiDefinition(), root, viewport, theme) }
                if (available(component)) {
                    check(result.isSuccess) { "Admitted Web definition $component failed: ${result.exceptionOrNull()?.message}" }
                    val host = result.getOrThrow()
                    host.close()
                    check(root.hasChildNodes().not()) { "Web component did not release its DOM: $component" }
                } else {
                    result.getOrNull()?.close()
                    check(result.exceptionOrNull() is UnsupportedOperationException) { "Web component availability changed: $component" }
                    check(root.hasChildNodes().not()) { "Rejected Web component retained its DOM: $component" }
                }
            } finally {
                root.parentNode?.removeChild(root)
            }
        }
    }

    /**
     * Admits a fixture selected by the independently exported build inventory.
     */
    fun requireAvailable(component: ComponentWorkload) {
        require(available(component)) { "This canonical component definition is unavailable on the Web host: $component" }
    }

    private fun supported(): List<ComponentWorkload> = ComponentWorkload.entries.filter(::available)

    private fun available(component: ComponentWorkload): Boolean =
        when (component) {
            ComponentWorkload.Row, ComponentWorkload.FlowRow, ComponentWorkload.Column, ComponentWorkload.Stack,
            ComponentWorkload.Grid, ComponentWorkload.Spacer, ComponentWorkload.Observe, ComponentWorkload.Button,
            ComponentWorkload.VirtualList, ComponentWorkload.SelectionList, ComponentWorkload.ProgressBar,
            -> true

            ComponentWorkload.Text, ComponentWorkload.TextField, ComponentWorkload.TextArea, ComponentWorkload.Checkbox,
            ComponentWorkload.CycleButton, ComponentWorkload.Slider, ComponentWorkload.Tab, ComponentWorkload.ScrollArea,
            ComponentWorkload.Scrollbar, ComponentWorkload.Image, ComponentWorkload.Canvas, ComponentWorkload.TiledImage,
            ComponentWorkload.Slot, ComponentWorkload.PlayerHead, ComponentWorkload.LoadingIndicator,
            -> false
        }
}
