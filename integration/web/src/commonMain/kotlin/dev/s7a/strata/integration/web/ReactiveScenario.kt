package dev.s7a.strata.integration.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf

/**
 * Independent deterministic state and declarations shared by JVM and browser acceptance runs.
 */
internal class ReactiveScenario {
    private val advanced = mutableStateOf(false)
    private val shown = mutableStateOf(true)
    private val reversed = mutableStateOf(false)

    /**
     * Recreates a one-shot definition while retaining this scenario's caller-owned state.
     */
    fun definition(): ScreenDefinition =
        ScreenDefinition("Reactive runtime parity") {
            Column(spacing = 8) {
                Text("Strata runtime parity")
                Button("Advance", modifier = Modifier.Empty.onActivate { advance() })
                Button("Toggle", modifier = Modifier.Empty.onActivate { toggle() })
                Button("Reorder", modifier = Modifier.Empty.onActivate { reorder() })
                if (shown.value) {
                    Text(
                        when (advanced.value) {
                            false -> "Initial"
                            true -> "Changed"
                        },
                    )
                }
                val names = if (reversed.value) listOf("Beta", "Alpha") else listOf("Alpha", "Beta")
                for (name in names) Text(name, key = ElementKey(name))
            }
        }

    /**
     * Changes the branch value without replacing its retained element.
     */
    fun advance() {
        advanced.value = true
    }

    /**
     * Adds or removes the conditional region.
     */
    fun toggle() {
        shown.value = shown.value.not()
    }

    /**
     * Reorders keyed siblings without changing their identity.
     */
    fun reorder() {
        reversed.value = reversed.value.not()
    }

    /**
     * Common logical geometry used independently at build and browser startup.
     */
    companion object {
        val viewport: IntSize = IntSize(400, 320)
    }
}
