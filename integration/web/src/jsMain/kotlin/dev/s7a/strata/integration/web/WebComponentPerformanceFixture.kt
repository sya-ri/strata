package dev.s7a.strata.integration.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.BrowserPerformanceJson
import dev.s7a.strata.performance.BrowserPerformanceMeter
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.quality.benchmark.ComponentWorkload
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.WebUiHost
import dev.s7a.strata.runtime.web.mountWeb
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * Actual browser operations over one canonical component definition, with all measurement delegated to the kit.
 */
internal class WebComponentPerformanceFixture(
    private val theme: WebTheme,
    private val component: ComponentWorkload,
    private val phase: PerformancePhase,
) {
    init {
        WebComponentPerformanceContract.requireAvailable(component)
        require(phase in WebComponentPerformanceContract.phases)
    }

    private val root =
        (document.createElement("div") as HTMLElement).apply {
            style.position = "absolute"
            style.left = "0px"
            style.top = "0px"
            checkNotNull(document.body).appendChild(this)
        }
    private var host: WebUiHost? = null

    /**
     * Transfers readiness, operation and terminal cleanup to the common browser meter.
     */
    fun measure(): Promise<String> =
        BrowserPerformanceMeter()
            .measure(
                ready = { root.isConnected },
                beforeSample = {
                    if (phase == PerformancePhase.Release || (phase != PerformancePhase.Initial && host == null)) mount()
                },
                afterSample = {
                    if (phase == PerformancePhase.Initial || phase == PerformancePhase.Release) {
                        host?.close()
                        host = null
                        check(root.hasChildNodes().not())
                    }
                },
                cleanup = ::close,
                operation = ::perform,
            ).then { BrowserPerformanceJson.sample(it) }

    private fun mount() {
        host = mountWeb(component.uiDefinition(), root, WebComponentPerformanceContract.viewport, theme)
    }

    private fun perform(index: Int) {
        when (phase) {
            PerformancePhase.Initial -> mount()
            PerformancePhase.Idle -> checkNotNull(host).render(WebComponentPerformanceContract.viewport)
            PerformancePhase.Resize -> checkNotNull(host).render(IntSize(320 + index % 2, 240 + index % 2))
            PerformancePhase.Release -> checkNotNull(host).close()
            PerformancePhase.Prepare, PerformancePhase.Update, PerformancePhase.Input -> error("This fixture phase is not registered: $phase")
        }
    }

    private fun close() {
        try {
            host?.close()
        } finally {
            host = null
            root.parentNode?.removeChild(root)
        }
    }
}
