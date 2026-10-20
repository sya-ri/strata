package dev.s7a.strata.integration.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.web.WebFont
import dev.s7a.strata.runtime.web.WebUiResources
import dev.s7a.strata.runtime.web.mountWeb
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import kotlin.js.json

/**
 * Drives the shared interaction definition through real browser resources and host events.
 */
internal fun mountInteractionApplication() {
    MainScope().launch {
        val font = WebFont.load("StrataFixture", "strata-browser-test.ttf", 9)
        val fontWidth = font.measure("A日🙂")
        val scenario = InteractionScenario()
        val root = checkNotNull(document.getElementById("strata-root")) as HTMLElement
        val resources = WebUiResources(mapOf(InteractionScenario.imageId to scenario.frame), mapOf(InteractionScenario.fontId to font))
        val host = mountWeb(scenario.definition(), root, InteractionScenario.viewport, resources)
        window.asDynamic().strataInteractions =
            json(
                "update" to { scenario.advance() },
                "resize" to { width: Int, height: Int -> host.render(IntSize(width, height)) },
                "close" to {
                    host.close()
                    font.close()
                },
                "snapshot" to {
                    JSON.stringify(
                        json(
                            "field" to scenario.field.value,
                            "fontWidth" to fontWidth,
                            "area" to scenario.area.value,
                            "scroll" to scenario.scroll.metrics.offset,
                            "list" to scenario.list.scrollState.metrics.offset,
                            "zoom" to scenario.navigation.metrics.zoom,
                            "centerX" to scenario.navigation.metrics.center.x,
                            "centerY" to scenario.navigation.metrics.center.y,
                            "subscriptions" to scenario.activeSubscriptions,
                            "actions" to scenario.actions.value,
                        ),
                    )
                },
            )
        root.setAttribute("data-mounted", "true")
        window.addEventListener("pagehide", {
            host.close()
            font.close()
        })
    }
}
