package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.TextInputAppearance
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Updates retained native editing elements without replacing selection or composition.
 */
internal object WebEditorDom {
    /**
     * Applies only changed canonical values; active composition remains owned by the native editor.
     */
    fun update(
        element: HTMLElement,
        editor: WebEditorPresentation,
        resources: WebUiResources,
    ) {
        val style = element.style
        style.font = editor.font
        style.padding = "3px"
        style.border = "1px solid #777"
        style.lineHeight = "${9 + editor.lineSpacing}px"
        style.resize = "none"
        style.whiteSpace = if (editor.wrap) "pre-wrap" else "pre"
        style.overflowWrap = if (editor.wrap) "anywhere" else "normal"
        style.color = cssColor(editor.style.color.value)
        style.setProperty("pointer-events", "auto")
        element.setAttribute("data-strata-editor", editor.identity.toString())
        val composing = element.hasAttribute("data-strata-composing")
        when (element) {
            is HTMLInputElement -> field(element, editor, composing)
            is HTMLTextAreaElement -> area(element, editor, composing)
        }
        appearance(element, editor, resources)
    }

    private fun field(
        element: HTMLInputElement,
        editor: WebEditorPresentation,
        composing: Boolean,
    ) {
        element.type = "text"
        element.maxLength = editor.maxLength
        element.disabled = editor.enabled.not()
        if (composing) return
        element.setAttribute("value", editor.value)
        if (element.value == editor.value) return
        val start = element.selectionStart ?: 0
        val end = element.selectionEnd ?: start
        element.value = editor.value
        element.setSelectionRange(minOf(start, editor.value.length), minOf(end, editor.value.length))
    }

    private fun area(
        element: HTMLTextAreaElement,
        editor: WebEditorPresentation,
        composing: Boolean,
    ) {
        element.maxLength = editor.maxLength
        element.disabled = editor.enabled.not()
        element.wrap = if (editor.wrap) "soft" else "off"
        if (composing) return
        val start = element.selectionStart ?: 0
        val end = element.selectionEnd ?: start
        val changed = element.value != editor.value
        if (element.defaultValue != editor.value) element.defaultValue = editor.value
        if (changed) {
            element.value = editor.value
            element.setSelectionRange(minOf(start, editor.value.length), minOf(end, editor.value.length))
        }
        if (element.scrollTop != editor.scrollOffset) element.scrollTop = editor.scrollOffset
    }

    private fun appearance(
        element: HTMLElement,
        editor: WebEditorPresentation,
        resources: WebUiResources,
    ) {
        val style = element.style
        val appearance = editor.appearance
        if (appearance is TextInputAppearance.Custom) {
            style.setProperty("caret-color", cssColor(appearance.caretColor.value))
            val source =
                when {
                    editor.enabled.not() -> appearance.disabled
                    editor.focused -> appearance.focused
                    else -> appearance.normal
                }
            val image =
                when (source) {
                    is ImageSource.Pixels -> source.image
                    is ImageSource.Resource -> checkNotNull(resources.images[source.id])
                }
            val border = appearance.border
            style.padding = "${maxOf(0, 4 - border.top)}px ${maxOf(0, 4 - border.right)}px ${maxOf(0, 4 - border.bottom)}px ${maxOf(0, 4 - border.left)}px"
            style.backgroundColor = "transparent"
            style.borderWidth = "${border.top}px ${border.right}px ${border.bottom}px ${border.left}px"
            style.setProperty("border-image-source", "url(${WebImageRasterizer.dataUrl(image)})")
            style.setProperty("border-image-slice", "${border.top} ${border.right} ${border.bottom} ${border.left} fill")
            style.setProperty("border-image-repeat", "repeat")
        } else {
            style.removeProperty("caret-color")
            style.removeProperty("border-image-source")
            style.removeProperty("border-image-slice")
            style.removeProperty("border-image-repeat")
        }
    }

    private fun cssColor(value: Int): String = "rgba(${value ushr 16 and 255},${value ushr 8 and 255},${value and 255},${(value ushr 24) / 255.0})"
}
