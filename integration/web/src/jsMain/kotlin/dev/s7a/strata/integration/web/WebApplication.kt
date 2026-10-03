package dev.s7a.strata.integration.web

import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.quality.benchmark.ComponentWorkload
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.runtime.web.renderWebDocument
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Promise

/**
 * Selects build rendering or interactive startup before creating any application state.
 */
public fun main() {
    val theme = mapOf("minecraft.html" to WebTheme.Minecraft)[window.location.pathname.substringAfterLast('/')] ?: WebTheme.Native
    window.asDynamic().strataPerformanceInventory = { WebComponentPerformanceContract.inventory() }
    if (WebLaunchMode.decode(window.location.search) == WebLaunchMode.Prerender) {
        val html = renderWebDocument(ReactiveScenario().definition(), ReactiveScenario.viewport, "Strata runtime parity", "application.js", theme)
        window.asDynamic().strataInitialDocument = html
    } else {
        val root = requireNotNull(document.getElementById("strata-root")) as HTMLElement
        val initial = root.firstElementChild
        val host = mountWeb(ReactiveScenario().definition(), root, ReactiveScenario.viewport, theme)
        check(initial === root.firstElementChild) { "Initial DOM was replaced during startup." }
        root.setAttribute("data-mounted", "true")
        val component = URLSearchParams(window.location.search).get("strata-component")?.let(ComponentWorkload::valueOf)
        window.asDynamic().strataVerifyPerformanceCollector = {
            // Preserve readable Kotlin failure details across Playwright's JavaScript error serialization.
            runCatching { WebComponentPerformanceContract.verify(theme) }.fold(
                onSuccess = { WebPerformanceCollectorCheck.verify() },
                onFailure = { Promise.resolve(it.toString()) },
            )
        }
        window.asDynamic().strataMeasurePerformance = { phase: String ->
            if (component == null) {
                WebPerformanceFixture(theme, PerformancePhase.valueOf(phase)).measure()
            } else {
                WebComponentPerformanceFixture(theme, component, PerformancePhase.valueOf(phase)).measure()
            }
        }
        window.addEventListener("pagehide", { host.close() })
    }
}
