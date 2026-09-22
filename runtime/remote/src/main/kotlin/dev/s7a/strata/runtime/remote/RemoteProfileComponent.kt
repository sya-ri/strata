package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.resource.ResourceId

/**
 * Wire identities for standard profile primitives reconstructed by the installed client runtime.
 */
internal enum class RemoteProfileComponent(
    path: String,
) {
    Text("text"),
    Button("button"),
    Tab("tab"),
    ProgressBar("progress_bar"),
    LoadingIndicator("loading_indicator"),
    Image("image"),
    PlayerHead("player_head"),
    Slot("slot"),
    Checkbox("checkbox"),
    CycleButton("cycle_button"),
    Slider("slider"),
    TextField("text_field"),
    TextArea("text_area"),
    ScrollArea("scroll_area"),
    Scrollbar("scrollbar"),
    Tooltip("tooltip"),
    MenuBackground("menu_background"),
    ContainerBackground("container_background"),
    ImageBackground("image_background"),
    NineSliceBackground("nine_slice_background"),
    ;

    val type = ProjectionType(ResourceId("strata", "profile/$path"))
}
