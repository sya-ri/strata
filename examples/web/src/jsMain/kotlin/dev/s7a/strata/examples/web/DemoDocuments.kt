package dev.s7a.strata.examples.web

import dev.s7a.strata.runtime.web.renderWebDocument
import kotlinx.browser.document
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.parsing.DOMParser

/**
 * Builds standalone documents from compiled catalog entries, with relative links usable in release snapshots.
 * Shell nodes stay outside the runtime-owned root and all reader text is assigned through DOM properties.
 */
internal object DemoDocuments {
    /**
     * Returns path/HTML records as JSON for the build harness without exposing an additional runtime API.
     */
    fun render(revision: String): String {
        require(revision.matches(Regex("(?:master|v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?|[0-9a-f]{40})"))) {
            "Invalid demo source revision."
        }
        val pages = mutableListOf(record("index.html", index(revision)))
        Demo.entries.forEach { demo -> pages.add(record("${demo.slug}/index.html", detail(demo, revision))) }
        return JSON.stringify(pages.toTypedArray())
    }

    private fun record(
        path: String,
        html: String,
    ): dynamic {
        val value = js("({})")
        value.path = path
        value.html = html
        return value
    }

    private fun index(revision: String): String {
        val output = document.implementation.createHTMLDocument("Strata web demos")
        val main = shell(output, "styles.css", "../index.html")
        main.setAttribute("class", "catalog")
        main.append("p", "STRATA", "wordmark")
        main.append("h1", "Choose a demo")
        main.append("p", "Three small worlds of state, layout, and interaction. Built with Strata. Ready to play in your browser.", "intro")
        val cards = main.append("div", className = "cards")
        Demo.entries.forEachIndexed { index, demo ->
            val card = cards.append("article", className = "card")
            card.append("p", "0${index + 1}", "number")
            card.append("h2", demo.title)
            card.append("p", demo.description)
            card.link("Open demo", "${demo.slug}/", "primary-link")
            card.link("View Kotlin source", source(demo, revision))
        }
        return serialize(output)
    }

    private fun detail(
        demo: Demo,
        revision: String,
    ): String {
        val output =
            DOMParser().parseFromString(
                renderWebDocument(demo.definition(), Demo.viewport, "${demo.title} | Strata", "../app.js"),
                "text/html",
            )
        val root = requireNotNull(output.getElementById("strata-root"))
        root.setAttribute("data-demo", demo.slug)
        val main = shell(output, "../styles.css", "../../index.html")
        main.setAttribute("class", "demo-page")
        main.link("All demos", "../")
        main.append("h1", demo.title)
        main.append("p", demo.description, "intro")
        val stage = main.append("div", className = "stage")
        stage.appendChild(root)
        main.append("p", "Enable JavaScript to use the controls.", "status").id = "demo-status"
        main.link("View Kotlin source", source(demo, revision))
        return serialize(output)
    }

    private fun shell(
        output: Document,
        stylesheet: String,
        apiUrl: String,
    ): Element {
        val head = requireNotNull(output.head)
        val charset = output.createElement("meta")
        charset.setAttribute("charset", "utf-8")
        if (head.querySelector("meta[charset]") == null) head.appendChild(charset)
        if (head.querySelector("meta[name=viewport]") == null) {
            val viewport = output.createElement("meta")
            viewport.setAttribute("name", "viewport")
            viewport.setAttribute("content", "width=device-width, initial-scale=1")
            head.appendChild(viewport)
        }
        val css = output.createElement("link")
        css.setAttribute("rel", "stylesheet")
        css.setAttribute("href", stylesheet)
        head.appendChild(css)
        requireNotNull(output.documentElement).setAttribute("lang", "en")
        val body = requireNotNull(output.body)
        val nav = body.append("nav")
        nav.setAttribute("aria-label", "Main navigation")
        nav.link("STRATA", apiUrl, "brand")
        nav.link("API reference", apiUrl)
        return body.append("main")
    }

    private fun Element.append(
        tag: String,
        text: String = "",
        className: String = "",
    ): Element {
        val child = requireNotNull(ownerDocument).createElement(tag)
        child.textContent = text
        if (className.isNotEmpty()) child.setAttribute("class", className)
        appendChild(child)
        return child
    }

    private fun Element.link(
        text: String,
        href: String,
        className: String = "",
    ) {
        append("a", text, className).setAttribute("href", href)
    }

    private fun source(
        demo: Demo,
        revision: String,
    ): String = "https://github.com/sya-ri/strata/blob/$revision/examples/web/src/jsMain/kotlin/dev/s7a/strata/examples/web/${demo.sourceFile}"

    private fun serialize(output: Document): String = "<!doctype html>\n${requireNotNull(output.documentElement).outerHTML}\n"
}
