package dev.s7a.strata.examples.velocity

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
 * Compiled proxy-only authoring example using the standard client components and server event subscriptions.
 */
public object VelocityDemoScreens {
    /**
     * Creates independent state inside the factory passed to VelocityUi.open.
     */
    public fun counter(): UiDefinition {
        val name = TextFieldState("Player", 32)
        val clicks = mutableStateOf(0)
        val greeting = mutableStateOf("This screen is owned by the proxy.")
        return UiDefinition("Velocity screen") {
            Column(Modifier.Empty.padding(8), spacing = 4) {
                Text(greeting.value)
                TextField(name, IntSize(180, 20))
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
