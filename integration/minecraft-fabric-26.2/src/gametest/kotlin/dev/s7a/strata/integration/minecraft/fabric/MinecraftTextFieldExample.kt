package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text-field
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
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
            Button(
                "Join Server",
                width = 200,
                modifier = Modifier.Empty.padding(Insets(left = 60, top = 168)).onPress {},
            )
            Button(
                "Cancel",
                width = 200,
                modifier = Modifier.Empty.padding(Insets(left = 60, top = 192)).onPress {},
            )
            Box(
                modifier = Modifier.Empty.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text("Direct Connection", modifier = Modifier.Empty.padding(Insets(top = 20)))
            }
            Text(
                "Server Address",
                style = MinecraftTextStyle.Inactive,
                modifier = Modifier.Empty.padding(Insets(left = 61, top = 100)),
            )
            TextField(
                address,
                modifier = Modifier.Empty.padding(Insets(left = 60, top = 116)),
            )
        }
    }
}
// showcase-source-end:text-field
