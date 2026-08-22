package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text-field
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.TextField
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the Minecraft 26.2 Direct Connection screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition with the actual EditBox and 200-pixel Button geometry.
 */
internal fun createDirectJoinScreenDefinition(): MinecraftScreenDefinition {
    val address = createMinecraftTextFieldState("play.example.net", maxLength = 128)
    return createMinecraftScreenDefinition("Direct Connection") {
        Box(modifier = Modifier.Empty.size(320, 240).menuBackground()) {
            Column(
                modifier = Modifier.Empty.size(320, 212),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Box(
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
                            style = MinecraftTextStyle.Inactive,
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
