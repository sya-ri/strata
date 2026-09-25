package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiScreenKind
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy

/**
 * Creates an API-only HUD with state retained across presentation switches.
 * Open and use it on the creating thread; start HUD interaction through the returned session when needed.
 */
@Suppress("unused") // Compiled downstream usage of common definitions, settings, state, and event receivers.
public fun createApiOnlyHud(): UiDefinition {
    val count = mutableStateOf(0)
    return UiDefinition(
        presentation = UiPresentation.Hud,
        category = UiCategory(ResourceId("example", "counter")),
        inputPolicy = UiInputPolicy.Movement,
        visibility = UiVisibilityPolicy(screens = mapOf(UiScreenKind.Chat to UiVisibility.KeepVisible)),
    ) {
        Column {
            Text("Count: ${count.value}")
            Button("Increment", modifier = Modifier.Empty.onActivate { count.value += 1 })
            Button("Screen", modifier = Modifier.Empty.onActivate { switch(UiPresentation.Screen) })
            Button("HUD", modifier = Modifier.Empty.onActivate { switch(UiPresentation.Hud) })
            Button("Close", modifier = Modifier.Empty.onActivate { close() })
        }
    }
}
