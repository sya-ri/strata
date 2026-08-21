package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds the literal Text component used by the verified ConfirmScreen title.
 *
 * @return callback-lifetime content using the implicit Minecraft component context.
 */
internal fun textExample(): MinecraftUiContext.() -> Element =
    {
        buildUi {
            Box(
                modifier = Modifier.Empty.size(150, 20),
                contentAlignment = Alignment.Center,
            ) {
                Text("Confirm action")
            }
        }
    }
// showcase-source-end:text
