package dev.s7a.strata.integration.web

import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.runtime.web.renderWebDocument
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Selects build rendering or interactive startup before creating any application state.
 */
public fun main() {
    val theme = mapOf("minecraft.html" to WebTheme.Minecraft)[window.location.pathname.substringAfterLast('/')] ?: WebTheme.Native
    if (WebLaunchMode.decode(window.location.search) == WebLaunchMode.Prerender) {
        val html = renderWebDocument(ReactiveScenario().definition(), ReactiveScenario.viewport, "Strata runtime parity", "application.js", theme)
        window.asDynamic().strataInitialDocument = html
    } else {
        val root = requireNotNull(document.getElementById("strata-root")) as HTMLElement
        val initial = root.firstElementChild
        val host = mountWeb(ReactiveScenario().definition(), root, ReactiveScenario.viewport, theme)
        check(initial === root.firstElementChild) { "Initial DOM was replaced during startup." }
        root.setAttribute("data-mounted", "true")
        window.addEventListener("pagehide", { host.close() })
    }
}
