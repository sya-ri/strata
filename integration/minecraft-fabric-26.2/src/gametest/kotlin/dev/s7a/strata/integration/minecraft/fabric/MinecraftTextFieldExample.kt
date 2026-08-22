package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text-field
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the Minecraft 26.2 Direct Connection screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition with the actual EditBox and 200-pixel Button geometry.
 */
internal fun createDirectJoinScreenDefinition(): ScreenDefinition {
    val address = TextFieldState("play.example.net", maxLength = 128)
    return ScreenDefinition("Direct Connection") {
        Stack(modifier = Modifier.Empty.size(320, 240).menuBackground()) {
            Column(
                modifier = Modifier.Empty.size(320, 212),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Stack(
                    modifier = Modifier.Empty.size(320, 29),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text("Direct Connection")
                }
                Column(
                    modifier = Modifier.Empty.size(200, 112),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(spacing = 7) {
                        Text(
                            "Server Address",
                            style = TextStyle.Inactive,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        TextField(address)
                    }
                    Column(spacing = 4) {
                        Button(
                            "Join Server",
                            width = 200,
                            modifier = Modifier.Empty.onPress {},
                        )
                        Button(
                            "Cancel",
                            width = 200,
                            modifier = Modifier.Empty.onPress {},
                        )
                    }
                }
            }
        }
    }
}
// showcase-source-end:text-field
