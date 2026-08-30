package dev.s7a.strata.integration.docs

/**
 * Canonical decision guidance for every top-level modifier extension admitted to the public API.
 */
internal object ModifierDocumentationCatalog {
    /**
     * Human-readable modifier documentation rendered into the public skill.
     *
     * @property category functional group used for routing.
     * @property purpose decision-relevant usage guidance.
     */
    internal data class Entry(
        internal val category: String,
        internal val purpose: String,
    )

    /**
     * Exact documented surface keyed by compiled extension method name.
     *
     * Additions require an explicit documentation review before skill generation succeeds.
     */
    internal val entries: Map<String, Entry> =
        mapOf(
            "actionDispatcher" to Entry("Advanced actions", "Obtains the dispatcher installed by an `onAction` modifier when imperative typed dispatch is unavoidable."),
            "background" to Entry("Paint", "Paints an ARGB color behind the element without introducing a logical component."),
            "containerBackground" to Entry("Paint", "Paints the active resource-pack generic-container panel for a declared row count."),
            "fillMaxHeight" to Entry("Size", "Fills the bounded height supplied by the parent while preserving intrinsic width."),
            "fillMaxSize" to Entry("Size", "Fills both bounded axes supplied by the parent."),
            "fillMaxWidth" to Entry("Size", "Fills the bounded width supplied by the parent while preserving intrinsic height."),
            "focusable" to Entry("Focus and text", "Makes an element participate in focused keyboard and text dispatch."),
            "height" to Entry("Size", "Requires one exact logical height."),
            "heightIn" to Entry("Size", "Constrains logical height to an optional minimum and maximum."),
            "imageBackground" to Entry("Paint", "Paints immutable pixels or a resource-pack resource behind any component, using typed stretch or tile mapping."),
            "initialFocus" to Entry("Focus and text", "Requests initial focus when the retained node first attaches."),
            "menuBackground" to Entry("Paint", "Paints the active resource-pack menu background without creating a separate background component."),
            "onAction" to Entry("Advanced actions", "Handles an extensible typed action key; prefer a focused built-in action extension when one exists."),
            "onCapturedPointerEvent" to Entry("Pointer", "Captures a consumed press for one button, forwards movement and matching drag/release outside bounds or ancestor clips, and reports cancellation when ownership ends early."),
            "onCharacterInput" to Entry("Focus and text", "Handles committed character input while the element is focused."),
            "onCheckedChange" to Entry("Component actions", "Receives the next boolean value emitted by `Checkbox`."),
            "onCycle" to Entry("Component actions", "Receives the next typed value emitted by `CycleButton`."),
            "onDrag" to Entry("Pointer", "Handles pointer drag events or invokes a simple action overload."),
            "onFocusChanged" to Entry("Focus and text", "Observes focus gain and loss for the retained element."),
            "onHover" to Entry("Pointer", "Observes pointer enter and exit without adding hover state to a component signature."),
            "onKeyEvent" to Entry("Focus and text", "Handles every focused key event before a narrower built-in behavior."),
            "onKeyPress" to Entry("Focus and text", "Handles a focused key press."),
            "onKeyRelease" to Entry("Focus and text", "Handles a focused key release."),
            "onLeadingItemsRequested" to Entry("Component actions", "Requests items before the current virtual-list boundary for prepend-style infinite loading."),
            "onMove" to Entry("Pointer", "Handles pointer movement or invokes a simple action overload."),
            "onPointerEvent" to Entry("Pointer", "Handles every pointer event when a specialized pointer extension is insufficient."),
            "onPreedit" to Entry("Focus and text", "Handles input-method preedit updates while focused."),
            "onPress" to Entry("Pointer", "Handles a pointer press or invokes a simple action overload; buttons do not own an `onPress` parameter."),
            "onRelease" to Entry("Pointer", "Handles a pointer release or invokes a simple action overload."),
            "onScroll" to Entry("Pointer", "Handles pointer-wheel input or invokes a simple action overload."),
            "onSelectionChange" to Entry("Component actions", "Receives the stable key selected by `SelectionList`."),
            "onSliderChange" to Entry("Component actions", "Receives the normalized value emitted by `Slider`."),
            "onTextInput" to Entry("Focus and text", "Handles every focused text-input event when committed-character and preedit handlers are too narrow."),
            "onTrailingItemsRequested" to Entry("Component actions", "Requests items after the current virtual-list boundary for append-style infinite loading."),
            "padding" to Entry("Layout", "Adds checked local insets around an element; use parent spacing and alignment for sibling structure."),
            "panZoom" to Entry("Pointer", "Pans a caller-owned `PanZoomState` with a captured button drag and zooms around the pointer with the vertical wheel delta."),
            "semantics" to Entry("Semantics", "Adds unresolved accessible semantics without coupling them to rendering."),
            "size" to Entry("Size", "Requires one exact logical width and height."),
            "sizeIn" to Entry("Size", "Constrains logical width and height to optional minimums and maximums."),
            "tooltip" to Entry("Semantics", "Associates unresolved tooltip text and optional positioning with the element."),
            "width" to Entry("Size", "Requires one exact logical width."),
            "widthIn" to Entry("Size", "Constrains logical width to an optional minimum and maximum."),
        )
}
