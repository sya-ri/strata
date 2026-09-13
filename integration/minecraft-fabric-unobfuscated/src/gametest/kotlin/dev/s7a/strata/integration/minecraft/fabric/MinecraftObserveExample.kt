package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:observe
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Creates a deterministic observed status region; live applications supply their own revisioned sources.
 */
internal fun createObserveShowcaseScreenDefinition(): ScreenDefinition {
    val status =
        StateSource<String> {
            StateSubscription(StateSnapshot(StateRevision(0), "Connected")) {}
        }
    return ScreenDefinition("Observe showcase") {
        Observe(
            status,
            modifier =
                Modifier.Empty
                    .size(160, 48)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .padding(8),
        ) { value ->
            Column(spacing = 4) {
                Text(value)
                Text(status)
            }
        }
    }
}
// showcase-source-end:observe
