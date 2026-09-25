package dev.s7a.strata.examples.paper

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Compiled Paper authoring example using the same DSL as local screens.
 * State and handlers stay on the server; editing and activation use the client's installed standard components.
 */
public object PaperDemoScreens {
    /**
     * Creates independent owner-thread state and a one-shot definition for one player.
     */
    public fun counter(): UiDefinition {
        val clicks = mutableStateOf(0)
        val name = TextFieldState("Player", 32)
        val greeting = mutableStateOf("Enter a name and press the button.")
        return UiDefinition("Paper screen") {
            Column(modifier = Modifier.Empty.padding(8), spacing = 4) {
                Text(greeting.value)
                TextField(name, IntSize(160, 20))
                Button(
                    "Count: ${clicks.value}",
                    modifier =
                        Modifier.Empty.onActivate {
                            clicks.value += 1
                            greeting.value = "Hello, ${name.value}."
                        },
                )
            }
        }
    }
}
