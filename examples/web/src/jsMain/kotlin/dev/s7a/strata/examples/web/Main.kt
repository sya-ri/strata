package dev.s7a.strata.examples.web

import dev.s7a.strata.runtime.web.mountWeb
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.PageTransitionEvent

/**
 * Installs the build-only rendering bridge and adopts an initial demo when its root is present.
 * Browser back/forward cache suspends a live host; terminal navigation closes its resources.
 */
public fun main() {
    window.asDynamic().strataDemoDocuments = { revision: String -> DemoDocuments.render(revision) }
    val root = document.getElementById("strata-root") as? HTMLElement ?: return
    runCatching {
        val demo = Demo.decode(requireNotNull(root.getAttribute("data-demo")))
        val host = mountWeb(demo.definition(), root, Demo.viewport)
        document.getElementById("demo-status")?.textContent = "Ready to explore. Changes stay on this page."
        root.setAttribute("data-demo-ready", "true")
        window.addEventListener("pagehide", { event ->
            if ((event as PageTransitionEvent).persisted.not()) host.close()
        })
    }.getOrElse { failure ->
        document.getElementById("demo-status")?.textContent = "Unable to start this demo. Reload to try again."
        throw failure
    }
}
