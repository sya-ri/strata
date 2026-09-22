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
    PrimaryPress("primary_press"),
    ActivationKeys("activation_keys"),
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
    Release("release"),
    Move("move"),
    Scroll("scroll"),
    Drag("drag"),
    Hover("hover"),
    Focusable("focusable"),
    InitialFocus("initial_focus"),
    FocusChanged("focus_changed"),
    ;

    public val type: ProjectionType = ProjectionType(ResourceId("strata", path))

    /**
     * Creates a detached positional property snapshot without a server event endpoint.
     */
    public fun properties(vararg values: ProjectionValue): DeclarationProjection<ProjectionValue.Sequence> = DeclarationProjection(type, ProjectionValue.Sequence(values.toList())) { value, _ -> value }

    /**
     * Projects a server-owned no-argument callback as an endpoint with an empty event payload.
     */
    public fun callback(action: () -> Unit): DeclarationProjection<() -> Unit> =
        DeclarationProjection(type, action) { callback, scope ->
            val endpoint =
                scope.action(
                    ProjectionAction(type, { value -> require(value === ProjectionValue.Absent) { "Expected an empty action payload." } }) { callback() },
                )
            ProjectionValue.Integer(endpoint)
        }
}
