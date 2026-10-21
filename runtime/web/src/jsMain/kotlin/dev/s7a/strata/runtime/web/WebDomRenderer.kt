package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextShadow
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.Text
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Owns DOM nodes for exactly the current frame, indexed by retained presentation identity or decorative command position.
 * Render validates the full command stream before mutating the root; close removes and releases owned children while retaining the caller-owned root.
 * All calls require the browser's owning agent. Caller text is assigned as textContent and is never parsed as markup.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // DOM adoption, validation and paint share one owner and lifetime.
internal class WebDomRenderer(
    private val root: HTMLElement,
    private val theme: WebTheme = WebTheme.Native,
    private var resources: WebUiResources? = WebUiResources(),
) : AutoCloseable {
    private var nodes = emptyMap<String, HTMLElement>()
    private val owned = LinkedHashSet<HTMLElement>()
    private var initialized = false
    private var stylesheet: HTMLElement? = null
    private val originalTheme = root.getAttribute("data-strata-theme-root")

    /**
     * Applies one detached frame in paint order, preserving matching native element identity across reordering.
     */
    fun render(frame: RuntimeUiFrame) {
        val entries = entries(frame)
        if (initialized.not()) {
            adoptInitialDom(entries)
            stylesheet = installWebTheme(root, theme)
            initialized = true
        }
        val next = LinkedHashMap<String, HTMLElement>()
        root.style.position = "relative"
        root.style.width = "${frame.size.width}px"
        root.style.height = "${frame.size.height}px"
        root.setAttribute("data-strata-theme-root", theme.token)
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
            owned.add(element)
            element.setAttribute("data-strata-theme", theme.token)
            update(element, entry, frame.size)
            WebSemanticsDom.update(element, entry.semantic, entry.proxy)
            element.style.zIndex = entry.order.toString()
            val activeEditor = entry.editor != null && (root.ownerDocument?.activeElement === element || element.hasAttribute("data-strata-composing"))
            if (element === cursor) {
                cursor = cursor.nextSibling
            } else if (activeEditor.not() || element.parentNode !== root) {
                root.insertBefore(element, cursor)
            }
            next[entry.identity] = element
        }
        nodes.forEach { (identity, element) ->
            if (next[identity] !== element) release(element)
        }
        nodes = next
        entries.firstOrNull(::focusedEntry)?.let { entry ->
            val target = checkNotNull(nodes[entry.identity])
            if (root.ownerDocument?.activeElement !== target && target.isConnected) target.focus()
        }
    }

    private fun focusedEntry(entry: Entry): Boolean = entry.semantic?.let { it.owner?.let { owner -> owner.focused && owner.focusable } == true && it.semantics.disabled.not() } ?: (entry.editor?.focused == true || entry.presentation?.focused == true)

    override fun close() {
        owned.toList().forEach(::release)
        nodes = emptyMap()
        stylesheet?.let { it.parentNode?.removeChild(it) }
        stylesheet = null
        resources = null
        if (initialized) {
            if (originalTheme == null) root.removeAttribute("data-strata-theme-root") else root.setAttribute("data-strata-theme-root", originalTheme)
        }
    }

    private fun release(element: HTMLElement) {
        owned.remove(element)
        if (element is HTMLCanvasElement) {
            element.width = 0
            element.height = 0
        }
        element.parentNode?.removeChild(element)
    }

    @Suppress("CyclomaticComplexMethod") // Validate every native kind before mutating generated markup.
    private fun adoptInitialDom(entries: List<Entry>) {
        check(originalTheme == null || originalTheme == theme.token) { "Initial web root does not match the requested theme." }
        if (root.hasChildNodes().not()) return
        val initial = (0 until root.childNodes.length).map { index -> checkNotNull(root.childNodes.item(index)) }
        val elements = initial.filterIsInstance<HTMLElement>()
        check(initial.all { it is HTMLElement || (it is Text && it.data.isBlank()) }) {
            "Initial web HTML contains unexpected text outside retained elements."
        }
        check(elements.size == entries.size) { "Initial web HTML does not match the screen's element count." }
        val adopted = LinkedHashMap<String, HTMLElement>()
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
            check(entry.editor != null || element.textContent == entry.presentation?.label.orEmpty()) {
                "Initial web HTML does not match the screen's initial text; the screen factory must be deterministic."
            }
            if (element is HTMLButtonElement) {
                val disabled = entry.semantic?.semantics?.disabled ?: (entry.presentation?.enabled != true)
                check(element.disabled == disabled) {
                    "Initial web HTML does not match the screen's initial enabled state."
                }
            }
            if (element is HTMLProgressElement) {
                check(element.max == 1.0 && element.value == entry.presentation?.progress && element.hasAttribute("value")) {
                    "Initial web HTML does not match the screen's initial progress."
                }
            }
            if (entry.editor != null) {
                val value =
                    when (element) {
                        is HTMLInputElement -> element.value
                        is HTMLTextAreaElement -> element.value
                        else -> error("Initial editor has the wrong DOM type.")
                    }
                check(value == entry.editor.value) { "Initial web editor value does not match." }
            }
            adopted[entry.identity] = element
        }
        initial.filter { (it is HTMLElement).not() }.forEach { root.removeChild(it) }
        nodes = adopted
        owned.addAll(adopted.values)
    }

    private fun update(
        element: HTMLElement,
        entry: Entry,
        viewport: IntSize,
    ) {
        val bounds = entry.bounds
        val style = element.style
        style.position = "absolute"
        style.boxSizing = "border-box"
        style.left = "${bounds.left}px"
        style.top = "${bounds.top}px"
        style.width = "${bounds.width}px"
        style.height = "${bounds.height}px"
        style.font = theme.font
        style.whiteSpace = "pre"
        style.setProperty("clip-path", clipPath(bounds, entry.clip))
        style.transform = ""
        entry.scaled?.let { scaled ->
            val destination = scaled.destination
            val sx = destination.width / scaled.source.width
            val sy = destination.height / scaled.source.height
            style.left = "${destination.left}px"
            style.top = "${destination.top}px"
            style.width = "${scaled.source.width}px"
            style.height = "${scaled.source.height}px"
            style.transformOrigin = "0 0"
            style.transform = "scale($sx,$sy)"
            style.setProperty("clip-path", scaledClipPath(scaled, entry.clip))
        }
        style.backgroundColor = entry.background
        val presentation = entry.presentation
        if (presentation?.font != null) style.font = presentation.font
        style.lineHeight = presentation?.lineHeight?.let { "${it}px" }.orEmpty()
        val image = entry.image
        if (image != null) {
            paintImage(element as HTMLCanvasElement, image, viewport, entry.clip)
            return
        }
        if (entry.editor != null) {
            WebEditorDom.update(element, entry.editor, checkNotNull(resources))
            return
        }
        textStyle(element, presentation)
        style.setProperty("pointer-events", if (presentation?.kind == WebPresentation.Kind.Button) "auto" else "none")
        if (element.textContent != presentation?.label.orEmpty()) element.textContent = presentation?.label.orEmpty()
        if (element is HTMLButtonElement) {
            element.type = "button"
            element.disabled = entry.semantic?.semantics?.disabled ?: (presentation?.enabled != true)
        }
        if (element is HTMLProgressElement) {
            element.max = 1.0
            element.value = checkNotNull(presentation?.progress)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive command decoding preserves paint order and nested clips.
    private fun entries(frame: RuntimeUiFrame): List<Entry> {
        val clips = ArrayDeque<FloatRect>()
        val entries = ArrayList<Entry>()
        frame.drawCommands.forEachIndexed { index, command ->
            val count = entries.size
            when (command) {
                is DrawCommand.PushClip -> {
                    val bounds = floating(command.bounds)
                    clips.addLast(clips.lastOrNull()?.let { intersect(it, bounds) } ?: bounds)
                }

                is DrawCommand.PushFractionalClip -> {
                    clips.addLast(clips.lastOrNull()?.let { intersect(it, command.bounds) } ?: command.bounds)
                }

                DrawCommand.PopClip -> {
                    check(clips.removeLastOrNull() != null) { "Unmatched web clip end." }
                }

                is DrawCommand.FillRectangle -> {
                    val value = command.color.value
                    val color = cssColor(value)
                    entries.add(Entry("d$index", "span", command.bounds, clips.lastOrNull(), color, null))
                }

                is DrawCommand.Platform -> {
                    entries.add(nativeEntry(command, clips.lastOrNull()))
                }

                is DrawCommand.BlitImage -> {
                    entries.add(Entry("d$index", "canvas", command.destination, clips.lastOrNull(), "", null, image = command))
                }

                is DrawCommand.BlitImagePixels -> {
                    entries.add(Entry("d$index", "canvas", command.destination, clips.lastOrNull(), "", null, image = command))
                }

                is DrawCommand.SampledImage -> {
                    entries.add(Entry("d$index", "canvas", enclosing(command.destination), clips.lastOrNull(), "", null, image = command))
                }
            }
            if (count < entries.size) entries[entries.lastIndex] = entries.last().copy(order = index.toLong() * 2 + 1)
        }
        check(clips.isEmpty()) { "Unterminated web clip." }
        check(entries.map(Entry::identity).distinct().size == entries.size) { "Duplicate web presentation identity." }
        WebSemanticsDom.entries(frame.semantics).forEach { semantic ->
            val owner = checkNotNull(semantic.owner)
            val native = entries.indexOfFirst { it.order == owner.contentPaintOrder.toLong() * 2 + 1 && nativeRole(it) == semantic.semantics.role }
            if (0 <= native) {
                entries[native] = entries[native].copy(semantic = semantic)
            } else {
                entries.add(Entry("s${owner.identity}", WebSemanticsDom.tag(semantic.semantics.role), semantic.bounds, floating(owner.visibleBounds), "", null, order = owner.paintOrder.toLong() * 2, semantic = semantic, proxy = true))
            }
        }
        return entries.sortedBy(Entry::order)
    }

    private fun nativeRole(entry: Entry): SemanticsRole? = entry.presentation?.kind?.role ?: entry.editor?.let { if (it.multiline) SemanticsRole.TextArea else SemanticsRole.TextField }

    /**
     * Converts straight ARGB channels without dropping alpha or browser-dependent named colors.
     */
    private fun cssColor(value: Int): String = "rgba(${value ushr 16 and 255},${value ushr 8 and 255},${value and 255},${(value ushr 24) / 255.0})"

    private fun intersect(
        left: FloatRect,
        right: FloatRect,
    ): FloatRect {
        val x = maxOf(left.left, right.left)
        val y = maxOf(left.top, right.top)
        return FloatRect(x, y, maxOf(x, minOf(left.right, right.right)), maxOf(y, minOf(left.bottom, right.bottom)))
    }

    private fun clipPath(
        bounds: IntRect,
        clip: FloatRect?,
    ): String {
        if (clip == null) return "none"
        val visible = intersect(floating(bounds), clip)
        return "inset(${visible.top - bounds.top}px ${maxOf(0f, bounds.right - visible.right)}px ${maxOf(0f, bounds.bottom - visible.bottom)}px ${visible.left - bounds.left}px)"
    }

    /**
     * Detached validated presentation operation; contains no event handler or mutable model reference.
     */
    private data class Entry(
        val identity: String,
        val tag: String,
        val bounds: IntRect,
        val clip: FloatRect?,
        val background: String,
        val presentation: WebPresentation?,
        val editor: WebEditorPresentation? = null,
        val image: DrawCommand? = null,
        val scaled: WebScaledPresentation? = null,
        val order: Long = 0,
        val semantic: SemanticsEntry? = null,
        val proxy: Boolean = false,
    )

    private fun floating(bounds: IntRect): FloatRect = FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())

    private fun enclosing(bounds: FloatRect): IntRect = IntRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())

    private fun nativeEntry(
        command: DrawCommand.Platform,
        clip: FloatRect?,
    ): Entry {
        val scaled = command.command as? WebScaledPresentation
        return when (val presentation = scaled?.presentation ?: command.command) {
            is WebPresentation -> Entry("n${presentation.identity}", presentation.kind.tag, command.bounds, clip, "", presentation, scaled = scaled)
            is WebEditorPresentation -> Entry("n${presentation.identity}", if (presentation.multiline) "textarea" else "input", command.bounds, clip, "", null, presentation, scaled = scaled)
            else -> throw UnsupportedOperationException("The web renderer does not recognize this platform payload.")
        }
    }

    private fun scaledClipPath(
        scaled: WebScaledPresentation,
        clip: FloatRect?,
    ): String {
        if (clip == null) return "none"
        val bounds = scaled.destination
        val visible = intersect(bounds, clip)
        val sx = bounds.width / scaled.source.width
        val sy = bounds.height / scaled.source.height
        return "inset(${(visible.top - bounds.top) / sy}px ${maxOf(0f, bounds.right - visible.right) / sx}px ${maxOf(0f, bounds.bottom - visible.bottom) / sy}px ${(visible.left - bounds.left) / sx}px)"
    }

    private fun paintImage(
        canvas: HTMLCanvasElement,
        image: DrawCommand,
        viewport: IntSize,
        clip: FloatRect?,
    ) {
        canvas.style.setProperty("pointer-events", "none")
        canvas.style.setProperty("clip-path", "none")
        WebImageRasterizer.draw(canvas, image, viewport, clip)
    }

    private fun textStyle(
        element: HTMLElement,
        presentation: WebPresentation?,
    ) {
        val style = element.style
        if (presentation?.kind == WebPresentation.Kind.Text) {
            val foreground = if (presentation.enabled) presentation.style.color else presentation.style.disabledColor ?: presentation.style.color
            style.color = cssColor(foreground.value)
            style.textShadow =
                when (val shadow = presentation.style.shadow) {
                    null -> "none"
                    TextShadow.Automatic -> "1px 1px ${cssColor((foreground.value and 0xff000000.toInt()) or ((foreground.value and 0xfcfcfc) ushr 2))}"
                    is TextShadow.Color -> "1px 1px ${cssColor(shadow.color.value)}"
                }
        } else {
            style.color = ""
            style.textShadow = ""
        }
    }
}
