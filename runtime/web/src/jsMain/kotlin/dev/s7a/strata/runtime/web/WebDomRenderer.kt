package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.Text

/**
 * Owns DOM nodes for exactly the current frame, indexed by retained presentation identity or decorative command position.
 * Render validates the full command stream before mutating the root; close removes and releases owned children while retaining the caller-owned root.
 * All calls require the browser's owning agent. Caller text is assigned as textContent and is never parsed as markup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebDomRenderer(
    private val root: HTMLElement,
) : AutoCloseable {
    private var nodes = emptyMap<String, HTMLElement>()
    private var initialized = false

    /**
     * Applies one detached frame in paint order, preserving matching native element identity across reordering.
     */
    fun render(frame: RuntimeUiFrame) {
        val entries = entries(frame)
        if (initialized.not()) {
            adoptInitialDom(entries)
            initialized = true
        }
        val next = LinkedHashMap<String, HTMLElement>()
        root.style.position = "relative"
        root.style.width = "${frame.size.width}px"
        root.style.height = "${frame.size.height}px"
        var cursor = root.firstChild
        entries.forEach { entry ->
            val previous = nodes[entry.identity]
            val element =
                if (previous?.tagName?.lowercase() == entry.tag) {
                    previous
                } else {
                    root.ownerDocument?.createElement(entry.tag) as HTMLElement
                }
            element.setAttribute("data-strata-node", entry.identity)
            update(element, entry)
            if (element !== cursor) root.insertBefore(element, cursor) else cursor = cursor.nextSibling
            check(next.put(entry.identity, element) == null) { "Duplicate web presentation identity." }
        }
        nodes.forEach { (identity, element) ->
            if (next[identity] !== element) element.parentNode?.removeChild(element)
        }
        nodes = next
    }

    override fun close() {
        nodes.values.forEach { element -> element.parentNode?.removeChild(element) }
        nodes = emptyMap()
    }

    private fun adoptInitialDom(entries: List<Entry>) {
        if (root.hasChildNodes().not()) return
        val initial = (0 until root.childNodes.length).map { index -> checkNotNull(root.childNodes.item(index)) }
        val elements = initial.filterIsInstance<HTMLElement>()
        check(initial.all { it is HTMLElement || (it is Text && it.data.isBlank()) }) {
            "Initial web HTML contains unexpected text outside retained elements."
        }
        check(elements.size == entries.size) { "Initial web HTML does not match the screen's element count." }
        val adopted = LinkedHashMap<String, HTMLElement>()
        entries.zip(elements).forEach { (entry, element) ->
            check((0 until element.childNodes.length).all { element.childNodes.item(it) is Text }) {
                "Initial web HTML contains unexpected nested markup inside a retained element."
            }
            check(element.getAttribute("data-strata-node") == entry.identity && element.tagName.lowercase() == entry.tag) {
                "Initial web HTML does not match the screen's retained identities and element kinds."
            }
            check(element.textContent == entry.presentation?.label.orEmpty()) {
                "Initial web HTML does not match the screen's initial text; the screen factory must be deterministic."
            }
            if (element is HTMLButtonElement) {
                check(element.disabled == (entry.presentation?.enabled != true)) {
                    "Initial web HTML does not match the screen's initial enabled state."
                }
            }
            if (element is HTMLProgressElement) {
                check(element.max == 1.0 && element.value == entry.presentation?.progress && element.hasAttribute("value")) {
                    "Initial web HTML does not match the screen's initial progress."
                }
            }
            adopted[entry.identity] = element
        }
        initial.filter { (it is HTMLElement).not() }.forEach { root.removeChild(it) }
        nodes = adopted
    }

    private fun update(
        element: HTMLElement,
        entry: Entry,
    ) {
        val bounds = entry.bounds
        val style = element.style
        style.position = "absolute"
        style.boxSizing = "border-box"
        style.left = "${bounds.left}px"
        style.top = "${bounds.top}px"
        style.width = "${bounds.width}px"
        style.height = "${bounds.height}px"
        style.font = "16px sans-serif"
        style.whiteSpace = "pre"
        style.setProperty("clip-path", clipPath(bounds, entry.clip))
        style.backgroundColor = entry.background
        val presentation = entry.presentation
        style.setProperty("pointer-events", if (presentation == null) "none" else "auto")
        if (element.textContent != presentation?.label.orEmpty()) element.textContent = presentation?.label.orEmpty()
        if (element is HTMLButtonElement) {
            element.type = "button"
            element.disabled = presentation?.enabled != true
        }
        if (element is HTMLProgressElement) {
            element.max = 1.0
            element.value = checkNotNull(presentation?.progress)
        }
    }

    private fun entries(frame: RuntimeUiFrame): List<Entry> {
        val clips = ArrayDeque<IntRect>()
        val entries = ArrayList<Entry>()
        frame.drawCommands.forEachIndexed { index, command ->
            when (command) {
                is DrawCommand.PushClip -> {
                    clips.addLast(clips.lastOrNull()?.let { intersect(it, command.bounds) } ?: command.bounds)
                }

                DrawCommand.PopClip -> {
                    check(clips.removeLastOrNull() != null) { "Unmatched web clip end." }
                }

                is DrawCommand.FillRectangle -> {
                    val value = command.color.value
                    val color = "rgba(${value ushr 16 and 255},${value ushr 8 and 255},${value and 255},${(value ushr 24) / 255.0})"
                    entries.add(Entry("d$index", "span", command.bounds, clips.lastOrNull(), color, null))
                }

                is DrawCommand.Platform -> {
                    val presentation =
                        command.command as? WebPresentation
                            ?: throw UnsupportedOperationException("The web renderer does not recognize this platform payload.")
                    entries.add(Entry("n${presentation.identity}", presentation.kind.tag, command.bounds, clips.lastOrNull(), "", presentation))
                }

                else -> {
                    throw UnsupportedOperationException("The web renderer does not support this image command.")
                }
            }
        }
        check(clips.isEmpty()) { "Unterminated web clip." }
        check(entries.map(Entry::identity).distinct().size == entries.size) { "Duplicate web presentation identity." }
        return entries
    }

    private fun intersect(
        left: IntRect,
        right: IntRect,
    ): IntRect {
        val x = maxOf(left.left, right.left)
        val y = maxOf(left.top, right.top)
        return IntRect(x, y, maxOf(x, minOf(left.right, right.right)), maxOf(y, minOf(left.bottom, right.bottom)))
    }

    private fun clipPath(
        bounds: IntRect,
        clip: IntRect?,
    ): String {
        if (clip == null) return "none"
        val visible = intersect(bounds, clip)
        return "inset(${visible.top - bounds.top}px ${maxOf(0, bounds.right - visible.right)}px ${maxOf(0, bounds.bottom - visible.bottom)}px ${visible.left - bounds.left}px)"
    }

    /**
     * Detached validated presentation operation; contains no event handler or mutable model reference.
     */
    private data class Entry(
        val identity: String,
        val tag: String,
        val bounds: IntRect,
        val clip: IntRect?,
        val background: String,
        val presentation: WebPresentation?,
    )
}
