package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds the literal Text component used by the verified ConfirmScreen title.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun textExample(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Text") {
        Box(
            modifier = Modifier.Empty.size(150, 20),
            contentAlignment = Alignment.Center,
        ) {
            Text("Confirm action")
        }
    }
// showcase-source-end:text
