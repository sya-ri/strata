package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:button
import dev.s7a.strata.component.Button
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the pointer Button used by the verified ConfirmScreen action row.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun buttonExample(): ScreenDefinition =
    ScreenDefinition("Button") {
        Button(
            "Yes",
            modifier =
                Modifier.Empty
                    .onPress {}
                    .onHover {},
        )
    }
// showcase-source-end:button
