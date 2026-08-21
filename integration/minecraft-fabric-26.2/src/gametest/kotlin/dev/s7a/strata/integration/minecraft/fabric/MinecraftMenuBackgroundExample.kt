package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:menu-background
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds a profile-backed Minecraft menu-background example.
 *
 * @return callback-lifetime content using the implicit Minecraft component context.
 */
internal fun menuBackgroundExample(): MinecraftUiContext.() -> Element =
    {
        buildUi {
            Box(modifier = Modifier.Empty.size(32, 32)) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
            }
        }
    }
// showcase-source-end:menu-background
