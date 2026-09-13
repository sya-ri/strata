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
): String = renderWebHtml(definition, viewport, WebTheme.Native)

/**
 * Renders themed container markup with the same lifetime contract as [renderWebHtml].
 * Use [renderWebDocument] to include the theme stylesheet and root background in a standalone document.
 */
public fun renderWebHtml(
    definition: ScreenDefinition,
    viewport: IntSize,
    theme: WebTheme,
): String {
    val root = document.createElement("div") as HTMLElement
    return createWebHost(definition, root, viewport, theme).use { host ->
        host.prepare()
        root.innerHTML
    }
}

/**
 * Produces a complete initial HTML document on the browser agent and releases all rendering resources before returning.
 * The title and script URL are assigned through DOM properties, preserving caller text without interpreting it as markup.
 * The deferred application script must mount an independently created definition into the `strata-root` element using the same viewport.
 * The caller owns bundling the script and resolving its URL relative to the emitted document.
 * Rendering and cleanup failures propagate without returning a partial document.
 *
 * @param definition one-shot definition with deterministic initial state.
 * @param viewport initial logical viewport and root dimensions in CSS pixels.
 * @param title document title, preserved as text.
 * @param scriptUrl URL of the bundled application entry point, loaded after the initial document is parsed.
 * @return serialized HTML document including its doctype, initial body content, and deferred application script.
 */
public fun renderWebDocument(
    definition: ScreenDefinition,
    viewport: IntSize,
    title: String,
    scriptUrl: String,
): String = renderWebDocument(definition, viewport, title, scriptUrl, WebTheme.Native)

/**
 * Renders a standalone themed document, including its stylesheet before initial body content.
 * Client startup must pass the same [theme] to [mountWeb]; all other ownership follows [renderWebDocument].
 */
public fun renderWebDocument(
    definition: ScreenDefinition,
    viewport: IntSize,
    title: String,
    scriptUrl: String,
    theme: WebTheme,
): String {
    require(scriptUrl.isNotBlank()) { "The application script URL must not be blank." }
    val initialHtml = renderWebHtml(definition, viewport, theme)
    val output = document.implementation.createHTMLDocument(title)
    val head = requireNotNull(output.head)
    val charset = output.createElement("meta")
    charset.setAttribute("charset", "utf-8")
    head.insertBefore(charset, head.firstChild)
    val viewportMeta = output.createElement("meta")
    viewportMeta.setAttribute("name", "viewport")
    viewportMeta.setAttribute("content", "width=device-width, initial-scale=1")
    head.appendChild(viewportMeta)
    if (theme != WebTheme.Native) {
        val stylesheet = output.createElement("style")
        stylesheet.textContent = webThemeStyles(theme)
        head.appendChild(stylesheet)
    }
    val body = requireNotNull(output.body)
    body.style.margin = "0"
    if (theme == WebTheme.Minecraft) body.style.backgroundColor = "#171717"
    val root = output.createElement("div") as HTMLElement
    root.id = "strata-root"
    root.setAttribute("data-strata-theme-root", theme.token)
    root.style.position = "relative"
    root.style.width = "${viewport.width}px"
    root.style.height = "${viewport.height}px"
    root.innerHTML = initialHtml
    body.appendChild(root)
    val script = output.createElement("script")
    script.setAttribute("defer", "")
    script.setAttribute("src", scriptUrl)
    body.appendChild(script)
    return "<!doctype html>\n${requireNotNull(output.documentElement).outerHTML}"
}
