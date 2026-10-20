package dev.s7a.strata.runtime.web

import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * Projects immutable common semantics without owning behavior or retaining a live node.
 */
internal object WebSemanticsDom {
    /**
     * Combines outer modifier overrides with the native component's semantics for each logical owner.
     */
    fun entries(entries: List<SemanticsEntry>): List<SemanticsEntry> {
        val owners = LinkedHashMap<Long, SemanticsEntry>()
        entries.forEach { entry ->
            val owner = entry.owner ?: return@forEach
            val previous = owners[owner.identity]
            val first = previous?.semantics
            val next = entry.semantics
            owners[owner.identity] =
                if (first == null) {
                    entry
                } else {
                    SemanticsEntry(
                        checkNotNull(previous).bounds,
                        Semantics(first.label ?: next.label, first.role ?: next.role, first.value ?: next.value, first.disabled || next.disabled, first.selected ?: next.selected, first.checked ?: next.checked),
                        checkNotNull(previous.owner),
                    )
                }
        }
        return owners.values.toList()
    }

    /**
     * Selects a native element kind for a custom semantic surface.
     */
    fun tag(role: SemanticsRole?): String = if (role == SemanticsRole.Button || role == SemanticsRole.CycleButton) "button" else "span"

    /**
     * Applies role, state and native focus eligibility to an existing presentation or a transparent surface.
     */
    @Suppress("CyclomaticComplexMethod") // Independent optional ARIA states follow the immutable common payload.
    fun update(
        element: HTMLElement,
        entry: SemanticsEntry?,
        proxy: Boolean,
    ) {
        val attributes = listOf("role", "aria-label", "aria-valuetext", "aria-disabled", "aria-selected", "aria-pressed", "aria-checked", "aria-multiline", "aria-hidden", "data-strata-owner", "tabindex")
        attributes.forEach(element::removeAttribute)
        if (entry == null) return
        val owner = checkNotNull(entry.owner)
        val value = entry.semantics
        element.setAttribute("data-strata-owner", owner.identity.toString())
        role(value.role)?.let { element.setAttribute("role", it) }
        value.label?.let { element.setAttribute("aria-label", resolve(it)) }
        value.value?.let { element.setAttribute("aria-valuetext", resolve(it)) }
        element.setAttribute("aria-disabled", value.disabled.toString())
        if (owner.visibleBounds.width == 0 || owner.visibleBounds.height == 0) element.setAttribute("aria-hidden", "true")
        value.selected?.let { element.setAttribute(if (value.role == SemanticsRole.Button) "aria-pressed" else "aria-selected", it.toString()) }
        value.checked?.let { element.setAttribute("aria-checked", it.toString()) }
        if (value.role == SemanticsRole.TextArea) element.setAttribute("aria-multiline", "true")
        element.tabIndex = if (owner.focusable && value.disabled.not()) 0 else -1
        if (element is HTMLButtonElement) element.disabled = value.disabled
        if (proxy) {
            element.style.backgroundColor = "transparent"
            element.style.border = "0"
            element.style.padding = "0"
            element.style.color = "transparent"
            element.style.fontSize = "0px"
            element.style.outline = "none"
            element.style.boxShadow = "none"
            element.style.setProperty("pointer-events", if (owner.focusable && value.disabled.not()) "auto" else "none")
        }
    }

    private fun role(role: SemanticsRole?): String? =
        when (role) {
            SemanticsRole.Button, SemanticsRole.CycleButton -> "button"
            SemanticsRole.TextField, SemanticsRole.TextArea -> "textbox"
            SemanticsRole.Tab -> "tab"
            SemanticsRole.Checkbox -> "checkbox"
            SemanticsRole.Slider -> "slider"
            SemanticsRole.ProgressBar -> "progressbar"
            SemanticsRole.SelectionList -> "listbox"
            SemanticsRole.Text -> null
            else -> "group"
        }

    private fun resolve(text: UiText): String =
        when (text) {
            is UiText.Literal -> {
                text.value
            }

            is UiText.Concatenated -> {
                text.parts.joinToString("") { resolve(it) }
            }

            is UiText.WithFont -> {
                resolve(text.text)
            }

            is UiText.Translated -> {
                require(text.arguments.isEmpty()) { "Web semantic translations with arguments require a text resolver." }
                when (val fallback = text.fallback) {
                    TranslationFallback.UseKey -> text.key
                    is TranslationFallback.Literal -> fallback.value
                }
            }

            is UiText.Platform -> {
                throw UnsupportedOperationException("Web platform semantics require a text resolver.")
            }
        }
}
