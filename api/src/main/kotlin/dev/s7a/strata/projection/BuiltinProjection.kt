package dev.s7a.strata.projection

import dev.s7a.strata.resource.ResourceId

/**
 * Stable schemas for standard declaration projections.
 * Wire consumers negotiate [type], never this enum's ordinal or a Kotlin class name.
 */
public enum class BuiltinProjection(
    path: String,
) {
    Row("row"),
    Column("column"),
    Stack("stack"),
    Grid("grid"),
    FlowRow("flow_row"),
    Spacer("spacer"),
    Observe("observe"),
    StateComponent("state_component"),
    Padding("padding"),
    Background("background"),
    Size("size"),
    ScaleToFit("scale_to_fit"),
    Weight("weight"),
    RowAlignment("row_alignment"),
    ColumnAlignment("column_alignment"),
    StackAlignment("stack_alignment"),
    ComponentAction("component_action"),
    CanvasPixels("canvas_pixels"),
    VirtualList("virtual_list"),
    ScrollPosition("scroll_position"),
    Semantics("semantics"),
    ObservedActivation("observed_activation"),
    PanZoom("pan_zoom"),
    TiledImage("tiled_image"),
    TiledImageLayer("tiled_image_layer"),
    TiledImagePosition("tiled_image_position"),
    Hover("hover"),
    Focusable("focusable"),
    InitialFocus("initial_focus"),
    FocusChanged("focus_changed"),
    PointerEvents("pointer_events"),
    PointerPress("pointer_press"),
    PointerRelease("pointer_release"),
    PointerMove("pointer_move"),
    PointerDrag("pointer_drag"),
    PointerScroll("pointer_scroll"),
    PointerCapture("pointer_capture"),
    KeyboardEvents("keyboard_events"),
    KeyPress("key_press"),
    KeyRelease("key_release"),
    TextInput("text_input"),
    CharacterInput("character_input"),
    PreeditInput("preedit_input"),
    ;

    public val type: ProjectionType = ProjectionType(ResourceId("strata", path))

    /**
     * Creates a detached positional property snapshot without a server event endpoint.
     */
    public fun properties(vararg values: ProjectionValue): DeclarationProjection<ProjectionValue.Sequence> = DeclarationProjection(type, ProjectionValue.Sequence(values.toList())) { value, _ -> value }
}
