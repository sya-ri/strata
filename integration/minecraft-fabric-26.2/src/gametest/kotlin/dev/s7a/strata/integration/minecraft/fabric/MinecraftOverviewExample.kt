package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:overview
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds the deterministic Minecraft 26.2 ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return callback-lifetime content reproducing the native title, message, and button-row geometry.
 */
internal fun confirmScreenContent(): MinecraftUiContext.() -> Element =
    {
        buildUi {
            Box(
                modifier = Modifier.Empty.size(320, 180),
                contentAlignment = Alignment.Center,
            ) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                    Row(spacing = 4) {
                        Button(
                            "Yes",
                            modifier =
                                Modifier.Empty
                                    .padding(Insets(top = 16))
                                    .onPress {},
                        )
                        Button(
                            "No",
                            modifier =
                                Modifier.Empty
                                    .padding(Insets(top = 16))
                                    .onPress {},
                        )
                    }
                }
            }
        }
    }
// showcase-source-end:overview
