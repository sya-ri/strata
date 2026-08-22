package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:stack
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a self-contained Stack showcase with a status badge painted over a Minecraft-profile button.
 *
 * @return one-shot definition demonstrating declaration-order painting from the button to the foreground badge.
 */
internal fun createStackShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Stack showcase") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(64, 64)
                    .background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            Button("Open", width = 56)
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(10, 10)
                        .background(ArgbColor(0xFFE53935.toInt()))
                        .align(Alignment.CenterEnd),
            )
        }
    }
// showcase-source-end:stack
