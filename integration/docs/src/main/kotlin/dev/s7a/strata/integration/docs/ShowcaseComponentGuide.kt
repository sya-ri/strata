package dev.s7a.strata.integration.docs

/**
 * Supplies reader-facing comparison guidance for the compiled component catalog.
 * All lookups are pure and exhaustive over the documented public component identities.
 */
internal object ShowcaseComponentGuide {
    /**
     * Groups supplied compiled sections by their UI responsibility without changing their order within a group.
     *
     * @param sections current compiled component sections.
     * @return Markdown comparison tables with links to examples and API details.
     */
    internal fun comparison(sections: List<ShowcaseOutput.Section>): String =
        Group.entries.joinToString("\n\n") { category ->
            val rows = sections.filter { section -> group(section.component) == category }
            """### ${category.title}

| Component | Choose it for | API |
| --- | --- | --- |
${rows.joinToString("\n") { section -> "| [${section.title}](#${section.slug}) | ${purpose(section.component)} | [Reference](${apiUrl(section.component)}) |" }}"""
        }

    /**
     * Describes the component's distinct purpose in one short sentence.
     *
     * @param component compiled public component identity.
     * @return reader-facing selection advice without version-specific implementation details.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun purpose(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Observe -> "Update one retained region when explicitly supplied sources change."
            DocumentedComponent.Row -> "Arrange siblings horizontally on one line."
            DocumentedComponent.FlowRow -> "Wrap horizontal siblings when the available width changes."
            DocumentedComponent.Column -> "Arrange siblings vertically."
            DocumentedComponent.Stack -> "Overlap children intentionally within one rectangle."
            DocumentedComponent.Grid -> "Align repeated content in a fixed number of columns."
            DocumentedComponent.Spacer -> "Reserve an intentional gap or flexible space in a layout."
            DocumentedComponent.Text -> "Display labels, messages, or wrapped read-only text."
            DocumentedComponent.TextField -> "Edit a single line of caller-owned text."
            DocumentedComponent.TextArea -> "Edit multiline text inside a scrollable viewport."
            DocumentedComponent.Button -> "Present an action with activation supplied by modifiers."
            DocumentedComponent.Checkbox -> "Let the user toggle a boolean value."
            DocumentedComponent.CycleButton -> "Cycle through a finite set of choices."
            DocumentedComponent.Slider -> "Adjust a value within a bounded numeric range."
            DocumentedComponent.Tab -> "Present an externally selected navigation option."
            DocumentedComponent.ScrollArea -> "Scroll content that extends beyond its viewport."
            DocumentedComponent.Scrollbar -> "Show and control the position of a shared scroll state."
            DocumentedComponent.VirtualList -> "Build only the visible rows of a large or loadable list."
            DocumentedComponent.SelectionList -> "Select entries in a virtualized list."
            DocumentedComponent.Image -> "Display an immutable image or a region of that image."
            DocumentedComponent.Canvas -> "Present frames from an external renderer or image producer."
            DocumentedComponent.TiledImage -> "Navigate large maps or images supplied as independent tiles."
            DocumentedComponent.PlayerHead -> "Display a player's skin face and hat layers."
            DocumentedComponent.Slot -> "Show an inventory slot bound to a typed slot source."
            DocumentedComponent.LoadingIndicator -> "Show that work is in progress when no completion value is available."
            DocumentedComponent.ProgressBar -> "Show progress toward a known completion value."
        }

    /**
     * Links to the existing Dokka function page containing all public overloads.
     * Fresh Pages staging verifies every expanded URL emitted in the generated catalog.
     *
     * @param component compiled public component identity.
     * @return absolute API documentation URL without an overload-specific fragment.
     */
    internal fun apiUrl(component: DocumentedComponent): String = "https://gh.s7a.dev/strata/api/dev.s7a.strata.component/-${component.slug}.html"

    private fun group(component: DocumentedComponent): Group =
        when (component) {
            DocumentedComponent.Row, DocumentedComponent.FlowRow, DocumentedComponent.Column,
            DocumentedComponent.Stack, DocumentedComponent.Grid, DocumentedComponent.Spacer, DocumentedComponent.Observe,
            -> Group.Layout

            DocumentedComponent.Text, DocumentedComponent.TextField, DocumentedComponent.TextArea -> Group.Text

            DocumentedComponent.Button, DocumentedComponent.Checkbox, DocumentedComponent.CycleButton,
            DocumentedComponent.Slider, DocumentedComponent.Tab,
            -> Group.Controls

            DocumentedComponent.ScrollArea, DocumentedComponent.Scrollbar,
            DocumentedComponent.VirtualList, DocumentedComponent.SelectionList,
            -> Group.Scrolling

            DocumentedComponent.Image, DocumentedComponent.Canvas, DocumentedComponent.TiledImage,
            DocumentedComponent.PlayerHead,
            -> Group.Images

            DocumentedComponent.Slot -> Group.Inventory

            DocumentedComponent.LoadingIndicator, DocumentedComponent.ProgressBar -> Group.Feedback
        }

    private enum class Group(
        val title: String,
    ) {
        Layout("Layout"),
        Text("Text and editing"),
        Controls("Actions and choices"),
        Scrolling("Scrolling and lists"),
        Images("Images and external rendering"),
        Inventory("Inventory"),
        Feedback("Progress feedback"),
    }
}
