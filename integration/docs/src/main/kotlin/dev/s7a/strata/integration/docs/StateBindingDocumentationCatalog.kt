package dev.s7a.strata.integration.docs

/**
 * Curated action payload, state, selection, resource-source, and slot-binding types needed for consumer authoring.
 */
internal object StateBindingDocumentationCatalog {
    /**
     * One source-backed public type group rendered into the skill.
     *
     * @property typeName public type or object name.
     * @property sourceFile API source filename.
     * @property purpose ownership and selection guidance.
     * @property packageName public JVM and source package.
     */
    internal data class Entry(
        internal val typeName: String,
        internal val sourceFile: String,
        internal val purpose: String,
        internal val packageName: String = COMPONENT_PACKAGE,
    )

    /**
     * Exact consumer-facing type groups whose declarations are generated into the skill.
     */
    internal val entries: List<Entry> =
        listOf(
            Entry(
                "ListLoadRequest",
                "ListLoadRequest.kt",
                "Immutable positive row demand delivered to leading and trailing virtual-list load handlers.",
                "dev.s7a.strata.action",
            ),
            Entry("CheckboxState", "CheckboxState.kt", "Caller-owned boolean value and owner-thread observation."),
            Entry("CycleButtonState", "CycleButtonState.kt", "Caller-owned generic option sequence, enum factory, formatting, and wraparound selection."),
            Entry("SliderState", "SliderState.kt", "Caller-owned finite range, optional steps, normalized fraction, and observation."),
            Entry("TextFieldState", "TextFieldState.kt", "Caller-owned bounded string value and observation."),
            Entry("TextAreaState", "TextAreaState.kt", "Caller-owned canonical LF text with a UTF-16 length limit and a stable owned vertical ScrollState. Immutable descriptions can be reused after detachment; simultaneous attachment with the same state throws IllegalStateException."),
            Entry("TextAreaViewport", "TextAreaViewport.kt", "Typed outer editor viewport from a positive width and visible line count, or an explicit positive IntSize."),
            Entry("TextLayout", "TextLayout.kt", "Single-line compatibility or structural multiline wrapping, line limits, overflow, and spacing.", "dev.s7a.strata.text"),
            Entry("TextWrap", "TextWrap.kt", "No soft wrapping, breakable-whitespace preference with scalar fallback, or scalar wrapping.", "dev.s7a.strata.text"),
            Entry("TextOverflow", "TextOverflow.kt", "Clip overflowing display text or append a fitting ellipsis without changing full semantics.", "dev.s7a.strata.text"),
            Entry("ScrollState", "ScrollState.kt", "Shared scroll metrics, mutation, observation, and geometry updates linking a viewport to optional controls."),
            Entry("VirtualListState", "VirtualListState.kt", "Virtual list navigation by index or stable key with its linked scroll state."),
            Entry("SelectionListState", "SelectionListState.kt", "Caller-owned stable-key selection layered over virtual-list navigation."),
            Entry("TabSelectionIndicator", "TabSelectionIndicator.kt", "Reusable selected-state presentation, including underline and one-root custom content."),
            Entry("ImageSource", "ImageSource.kt", "Detached pixels or a resource-pack-resolved image identifier."),
            Entry("PlayerSkinSource", "PlayerSkinSource.kt", "Detached pixels, current player, player name, or UUID resolved by the installed runtime."),
            Entry("SlotBinding", "SlotBinding.kt", "Immutable logical inventory locator retained without a Minecraft type."),
            Entry("Slots", "Slots.kt", "Validated factories for player inventory, logical container storage, and raw active-menu indices."),
        )

    private const val COMPONENT_PACKAGE = "dev.s7a.strata.component"
}
