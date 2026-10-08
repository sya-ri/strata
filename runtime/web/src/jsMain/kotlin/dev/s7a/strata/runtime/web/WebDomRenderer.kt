package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.Text
import org.w3c.dom.css.CSSStyleDeclaration

/**
 * Owns DOM nodes for exactly the current frame, indexed by retained presentation identity or decorative command position.
 * Render validates the full command stream before mutating the root; close removes and releases owned children while retaining the caller-owned root.
 * All calls require the browser's owning agent. Caller text is assigned as textContent and is never parsed as markup.
 * Each current element retains only its last applied detached entry; theme and static DOM properties belong exclusively to this renderer.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebDomRenderer(
    private val root: HTMLElement,
    private val theme: WebTheme = WebTheme.Native,
) : AutoCloseable {
    private var nodes = emptyMap<String, RetainedElement>()
    private var size: IntSize? = null
    private var uncommitted: MutableList<HTMLElement>? = null
    private var initialized = false
    private var closed = false
    private var stylesheet: HTMLElement? = null
    private val originalTheme = root.getAttribute("data-strata-theme-root")

    /**
     * Number of current DOM owners, including adopted elements awaiting their first complete update.
     */
    internal val retainedElementCount: Int get() = nodes.size

    /**
     * Applies one detached frame in paint order, preserving matching native element identity across reordering.
     */
    fun render(frame: RuntimeUiFrame) {
        check(closed.not()) { "The web renderer is closed." }
        val entries = entries(frame)
        val firstRender = initialized.not()
        if (initialized.not()) {
            adoptInitialDom(entries)
            stylesheet = installWebTheme(root, theme)
            root.style.position = "relative"
            initialized = true
        }
        val next = LinkedHashMap<String, RetainedElement>()
        if (size?.width != frame.size.width) root.style.width = "${frame.size.width}px"
        if (size?.height != frame.size.height) root.style.height = "${frame.size.height}px"
        if (firstRender) root.setAttribute("data-strata-theme-root", theme.token)
        size = frame.size
        var cursor = root.firstChild
        entries.forEach { entry ->
            val previous = nodes[entry.identity]
            val retained =
                if ((previous?.entry?.tag ?: previous?.element?.tagName?.lowercase()) == entry.tag) {
                    previous
                } else {
                    val element = checkNotNull(root.ownerDocument).createElement(entry.tag) as HTMLElement
                    (uncommitted ?: ArrayList<HTMLElement>().also { uncommitted = it }).add(element)
                    RetainedElement(element)
                }
            val element = retained.element
            if (retained.entry != entry) update(element, entry, retained.entry)
            retained.entry = entry
            if (element !== cursor) root.insertBefore(element, cursor) else cursor = cursor.nextSibling
            next[entry.identity] = retained
        }
        nodes.forEach { (identity, retained) ->
            if (next[identity] !== retained) retained.element.let { it.parentNode?.removeChild(it) }
        }
        nodes = next
        uncommitted = null
    }

    override fun close() {
        if (closed) return
        closed = true
        val previous = nodes
        val pending = uncommitted
        val previousStylesheet = stylesheet
        nodes = emptyMap()
        size = null
        uncommitted = null
        stylesheet = null
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { caught ->
                val primary = failure
                if (primary == null) failure = caught else if (primary !== caught) primary.addSuppressed(caught)
            }
        }
        previous.values.forEach { retained -> release { retained.element.let { it.parentNode?.removeChild(it) } } }
        pending?.forEach { element -> release { element.parentNode?.removeChild(element) } }
        previousStylesheet?.let { release { it.parentNode?.removeChild(it) } }
        if (initialized) {
            release {
                if (originalTheme == null) root.removeAttribute("data-strata-theme-root") else root.setAttribute("data-strata-theme-root", originalTheme)
            }
        }
        failure?.let { throw it }
    }

    private fun adoptInitialDom(entries: List<Entry>) {
        check(originalTheme == null || originalTheme == theme.token) { "Initial web root does not match the requested theme." }
        if (root.hasChildNodes().not()) return
        val initial = (0 until root.childNodes.length).map { index -> checkNotNull(root.childNodes.item(index)) }
        val elements = initial.filterIsInstance<HTMLElement>()
        check(initial.all { it is HTMLElement || (it is Text && it.data.isBlank()) }) {
            "Initial web HTML contains unexpected text outside retained elements."
        }
        check(elements.size == entries.size) { "Initial web HTML does not match the screen's element count." }
        val adopted = LinkedHashMap<String, RetainedElement>()
        entries.zip(elements).forEach { (entry, element) ->
            check((element.getAttribute("data-strata-theme") ?: WebTheme.Native.token) == theme.token) {
                "Initial web HTML does not match the requested theme."
            }
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
            adopted[entry.identity] = RetainedElement(element)
        }
        initial.filter { (it is HTMLElement).not() }.forEach { root.removeChild(it) }
        nodes = adopted
    }

    private fun update(
        element: HTMLElement,
        entry: Entry,
        previous: Entry?,
    ) {
        val bounds = entry.bounds
        val style = element.style
        if (previous == null) {
            element.setAttribute("data-strata-node", entry.identity)
            element.setAttribute("data-strata-theme", theme.token)
            style.position = "absolute"
            style.boxSizing = "border-box"
        }
        updateBounds(style, bounds, previous?.bounds)
        if (previous == null) {
            style.font = theme.font
            style.whiteSpace = "pre"
        }
        updateClip(style, entry, previous)
        if (previous?.background != entry.background) style.backgroundColor = entry.background
        val presentation = entry.presentation
        updateTextStyle(style, presentation, previous?.presentation, previous == null)
        if (previous == null || (previous.presentation == null) != (presentation == null)) style.setProperty("pointer-events", if (presentation == null) "none" else "auto")
        if (element.textContent != presentation?.label.orEmpty()) element.textContent = presentation?.label.orEmpty()
        updateNativeControl(element, presentation, previous?.presentation, previous == null)
    }

    private fun updateBounds(style: CSSStyleDeclaration, bounds: IntRect, previous: IntRect?) {
        if (previous?.left != bounds.left) style.left = "${bounds.left}px"
        if (previous?.top != bounds.top) style.top = "${bounds.top}px"
        if (previous?.width != bounds.width) style.width = "${bounds.width}px"
        if (previous?.height != bounds.height) style.height = "${bounds.height}px"
    }

    private fun updateClip(style: CSSStyleDeclaration, entry: Entry, previous: Entry?) {
        if (previous == null || previous.clip != entry.clip || (entry.clip != null && previous.bounds != entry.bounds)) {
            val clip = clipPath(entry.bounds, entry.clip)
            if (previous == null || clipPath(previous.bounds, previous.clip) != clip) style.setProperty("clip-path", clip)
        }
    }

    private fun updateTextStyle(style: CSSStyleDeclaration, presentation: WebPresentation?, previous: WebPresentation?, initial: Boolean) {
        val color = textColor(presentation)
        if (initial || textColor(previous) != color) style.color = color
        if (initial || (previous?.style == TextStyle.ContainerLabel) != (presentation?.style == TextStyle.ContainerLabel)) {
            style.textShadow = if (presentation?.style == TextStyle.ContainerLabel) "none" else ""
        }
    }

    private fun updateNativeControl(element: HTMLElement, presentation: WebPresentation?, previous: WebPresentation?, initial: Boolean) {
        if (element is HTMLButtonElement) {
            if (initial) element.type = "button"
            if (initial || previous?.enabled != presentation?.enabled) element.disabled = presentation?.enabled != true
        }
        if (element is HTMLProgressElement) {
            if (initial) element.max = 1.0
            if (initial || previous?.progress != presentation?.progress) element.value = checkNotNull(presentation?.progress)
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

    private fun textColor(presentation: WebPresentation?): String =
        if (theme == WebTheme.Minecraft && presentation?.kind == WebPresentation.Kind.Text) {
            when (presentation.style) {
                TextStyle.Inactive -> "#a0a0a0"
                TextStyle.ContainerLabel -> "#404040"
                else -> "#ffffff"
            }
        } else {
            ""
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

    /**
     * One current native identity and its last applied immutable value; adoption starts without a trusted style snapshot.
     */
    private class RetainedElement(
        val element: HTMLElement,
        var entry: Entry? = null,
    )
}
