package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.ui.UiDefinition

/**
 * Canonical component factories and their native capture geometry and accepted animation phases.
 */
internal enum class ComponentShowcase(
    val slug: String,
    val viewport: IntSize,
    val definition: (ComponentShowcaseAssets) -> UiDefinition,
    val pointer: IntOffset = IntOffset.Zero,
    val animationFrameTimes: List<FrameTime> = emptyList(),
    val scale: Int = 1,
) {
    Row("row", IntSize(136, 64), { createRowShowcaseScreenDefinition() }),
    FlowRow("flow-row", IntSize(168, 60), { createFlowRowShowcaseScreenDefinition() }),
    Column("column", IntSize(120, 64), { createColumnShowcaseScreenDefinition() }),
    Stack("stack", IntSize(64, 64), { createStackShowcaseScreenDefinition() }),
    Grid("grid", IntSize(64, 64), { createGridShowcaseScreenDefinition() }),
    Spacer("spacer", IntSize(160, 64), { createSpacerShowcaseScreenDefinition() }),
    Observe("observe", IntSize(160, 48), { createObserveShowcaseScreenDefinition() }),
    Text("text", IntSize(192, 88), { createTextShowcaseScreenDefinition() }, scale = 2),
    TextField("text-field", IntSize(216, 64), { createTextFieldShowcaseScreenDefinition() }, scale = 2),
    TextArea("text-area", IntSize(226, 80), { createTextAreaShowcaseScreenDefinition() }, scale = 2),
    Button("button", IntSize(166, 64), { createButtonShowcaseScreenDefinition() }),
    Checkbox("checkbox", IntSize(166, 36), { createCheckboxShowcaseScreenDefinition() }),
    CycleButton("cycle-button", IntSize(166, 36), { createCycleButtonShowcaseScreenDefinition() }),
    Slider("slider", IntSize(166, 36), { createSliderShowcaseScreenDefinition() }),
    Tab("tab", IntSize(160, 64), { createTabShowcaseScreenDefinition() }),
    ScrollArea("scroll-area", IntSize(120, 48), { createScrollAreaShowcaseScreenDefinition() }),
    Scrollbar("scrollbar", IntSize(94, 48), { createScrollbarShowcaseScreenDefinition() }),
    VirtualList("virtual-list", IntSize(120, 48), { createVirtualListShowcaseScreenDefinition() }),
    SelectionList("selection-list", IntSize(120, 48), { createSelectionListShowcaseScreenDefinition() }),
    Image("image", IntSize(64, 64), { createImageShowcaseScreenDefinition(it.image) }),
    Canvas("canvas", IntSize(96, 64), { createCanvasShowcaseScreenDefinition() }),
    TiledImage("tiled-image", IntSize(112, 88), { createTiledImageShowcaseScreenDefinition() }),
    Slot("slot", IntSize(64, 64), { createSlotShowcaseScreenDefinition() }, IntOffset(32, 32)),
    PlayerHead("player-head", IntSize(64, 64), { createPlayerHeadShowcaseScreenDefinition(it.playerSkin) }),
    LoadingIndicator(
        "loading-indicator",
        IntSize(32, 24),
        { createLoadingIndicatorShowcaseScreenDefinition() },
        animationFrameTimes =
            listOf(
                FrameTime(0L),
                FrameTime(300_000_000L),
                FrameTime(600_000_000L),
            ),
    ),
    ProgressBar("progress-bar", IntSize(116, 28), { createProgressBarShowcaseScreenDefinition() }),
    ;

    val physicalSize: IntSize = IntSize(Math.multiplyExact(viewport.width, scale), Math.multiplyExact(viewport.height, scale))
}
