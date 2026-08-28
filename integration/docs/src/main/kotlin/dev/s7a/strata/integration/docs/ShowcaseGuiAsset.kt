package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.resource.ResourceId

/**
 * Complete typed image inventory for the 26.2 profile and non-inventory documentation scenes.
 * Every image is decoded from an explicit caller input; no screenshot or generated image is an asset source.
 */
internal enum class ShowcaseGuiAsset(
    path: String,
    val size: IntSize,
    val metadata: ShowcaseGuiMetadata = ShowcaseGuiMetadata.None,
    namespace: String = "minecraft",
) {
    /**
     * Repeating menu texture used behind the root screen.
     */
    MenuBackground("textures/gui/menu_background.png", IntSize(16, 16)),

    /**
     * Original container sheet used for panel and inventory-slot source regions.
     */
    ContainerBackground("textures/gui/container/generic_54.png", IntSize(256, 256)),

    /**
     * Rear slot highlight with its four-pixel protected border.
     */
    SlotHighlightBack("textures/gui/sprites/container/slot_highlight_back.png", IntSize(24, 24), slice(4)),

    /**
     * Front slot highlight composited above slot contents.
     */
    SlotHighlightFront("textures/gui/sprites/container/slot_highlight_front.png", IntSize(24, 24), slice(4)),

    /**
     * Repeating background texture inside scrolling lists.
     */
    ListBackground("textures/gui/menu_list_background.png", IntSize(16, 16)),

    /**
     * Horizontal separator above list content.
     */
    HeaderSeparator("textures/gui/header_separator.png", IntSize(32, 2)),

    /**
     * Horizontal separator below list content.
     */
    FooterSeparator("textures/gui/footer_separator.png", IntSize(32, 2)),

    /**
     * Scrollbar track with a one-pixel protected border.
     */
    ScrollbarBackground("textures/gui/sprites/widget/scroller_background.png", IntSize(6, 32), slice(1)),

    /**
     * Resizable scrollbar thumb using the original track-width sprite.
     */
    ScrollbarThumb("textures/gui/sprites/widget/scroller.png", IntSize(6, 32), slice(1)),

    /**
     * Unselected checkbox in its resting state.
     */
    Checkbox("textures/gui/sprites/widget/checkbox.png", IntSize(20, 20)),

    /**
     * Unselected checkbox highlighted by hover or focus.
     */
    CheckboxHighlighted("textures/gui/sprites/widget/checkbox_highlighted.png", IntSize(20, 20)),

    /**
     * Selected checkbox in its resting state.
     */
    CheckboxSelected("textures/gui/sprites/widget/checkbox_selected.png", IntSize(20, 20)),

    /**
     * Selected checkbox highlighted by hover or focus.
     */
    CheckboxSelectedHighlighted("textures/gui/sprites/widget/checkbox_selected_highlighted.png", IntSize(20, 20)),

    /**
     * Normal slider track with its one-pixel protected border.
     */
    Slider("textures/gui/sprites/widget/slider.png", IntSize(200, 20), slice(1)),

    /**
     * Highlighted slider track using the same resizing contract.
     */
    SliderHighlighted("textures/gui/sprites/widget/slider_highlighted.png", IntSize(200, 20), slice(1)),

    /**
     * Normal slider handle with the original asymmetric bottom border.
     */
    SliderHandle("textures/gui/sprites/widget/slider_handle.png", IntSize(8, 20), ShowcaseGuiMetadata.NineSlice(Insets(2, 2, 2, 3))),

    /**
     * Highlighted slider handle preserving the original border geometry.
     */
    SliderHandleHighlighted("textures/gui/sprites/widget/slider_handle_highlighted.png", IntSize(8, 20), ShowcaseGuiMetadata.NineSlice(Insets(2, 2, 2, 3))),

    /**
     * Unfocused editable-field frame with a one-pixel protected border.
     */
    TextField("textures/gui/sprites/widget/text_field.png", IntSize(200, 20), slice(1)),

    /**
     * Focused editable-field frame using the same source dimensions.
     */
    TextFieldHighlighted("textures/gui/sprites/widget/text_field_highlighted.png", IntSize(200, 20), slice(1)),

    /**
     * Enabled button background with its three-pixel protected border.
     */
    Button("textures/gui/sprites/widget/button.png", IntSize(200, 20), slice(3)),

    /**
     * Highlighted enabled button background.
     */
    ButtonHighlighted("textures/gui/sprites/widget/button_highlighted.png", IntSize(200, 20), slice(3)),

    /**
     * Disabled button background with its distinct one-pixel border.
     */
    ButtonDisabled("textures/gui/sprites/widget/button_disabled.png", IntSize(200, 20), slice(1)),

    /**
     * Three-frame loading sheet whose metadata advances every six ticks.
     */
    LoadingIndicator("textures/gui/sprites/friends/loading.png", IntSize(5, 6), ShowcaseGuiMetadata.Animation(IntSize(5, 2), 6)),

    /**
     * Bundle-style progress outline with its two-pixel protected border.
     */
    ProgressBarBorder("textures/gui/sprites/container/bundle/bundle_progressbar_border.png", IntSize(12, 12), slice(2)),

    /**
     * Progress fill used below the completed state.
     */
    ProgressBarFill("textures/gui/sprites/container/bundle/bundle_progressbar_fill.png", IntSize(6, 6), slice(2)),

    /**
     * Completed progress fill using the same border geometry.
     */
    ProgressBarFull("textures/gui/sprites/container/bundle/bundle_progressbar_full.png", IntSize(6, 6), slice(2)),

    /**
     * Tooltip backdrop whose nine-pixel border remains fixed while resizing.
     */
    TooltipBackground("textures/gui/sprites/tooltip/background.png", IntSize(100, 100), slice(9)),

    /**
     * Tooltip outline whose metadata explicitly stretches the inner region.
     */
    TooltipFrame("textures/gui/sprites/tooltip/frame.png", IntSize(100, 100), ShowcaseGuiMetadata.NineSlice(Insets(10, 10, 10, 10), true)),

    /**
     * Social-interactions panel used by the composed screen example.
     */
    SocialPanel("textures/gui/sprites/social_interactions/background.png", IntSize(236, 34), slice(8)),

    /**
     * Search icon placed beside the social screen's editable query.
     */
    SearchIcon("textures/gui/sprites/icon/search.png", IntSize(12, 12)),

    /**
     * Original advancements sheet containing the example window frame.
     */
    AdvancementWindow("textures/gui/advancements/window.png", IntSize(256, 256)),

    /**
     * Repeating stone texture behind the advancements composition.
     */
    AdvancementBackground("textures/gui/advancements/backgrounds/stone.png", IntSize(16, 16)),

    /**
     * Completed advancement frame used by the example's obtained nodes.
     */
    AdvancementObtained("textures/gui/sprites/advancements/task_frame_obtained.png", IntSize(26, 26)),

    /**
     * Incomplete advancement frame used by the example's unobtained nodes.
     */
    AdvancementUnobtained("textures/gui/sprites/advancements/task_frame_unobtained.png", IntSize(26, 26)),

    /**
     * Explicit original Efe skin shared by the fixed offline player examples.
     */
    PlayerSkin("textures/entity/player/slim/efe.png", IntSize(64, 64)),

    /**
     * Repository-owned Mod texture used by the industrial composition example.
     */
    CoalGenerator("textures/gui/coal_generator.png", IntSize(1229, 1280), namespace = "strata_test"),
    ;

    /**
     * Canonical resource identifier shared by native and offline scene inputs.
     */
    val id: ResourceId = ResourceId(namespace, path)
}

private fun slice(border: Int): ShowcaseGuiMetadata.NineSlice = ShowcaseGuiMetadata.NineSlice(Insets(border, border, border, border))
