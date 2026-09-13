package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Renders detached initial HTML on the browser agent without installing input listeners or scheduling animation frames.
 * Returns the root's child markup, suitable for insertion into the root passed to [mountWeb].
 * The embedding root must establish relative positioning and the supplied viewport dimensions before client startup.
 * All retained nodes and subscriptions are closed before returning; caller-owned state remains usable.
 * Node attachment still runs, so the definition must provide deterministic initial data and keep browser-only application effects outside content.
 * Build and client must create independent definitions with the same initial state and viewport.
 * Transfer, rendering, serialization, and cleanup failures propagate with the primary failure preserved.
 */
public fun renderWebHtml(
    definition: ScreenDefinition,
    viewport: IntSize,
): String {
    val root = document.createElement("div") as HTMLElement
    return createWebHost(definition, root, viewport).use { host ->
        host.prepare()
        root.innerHTML
    }
}
