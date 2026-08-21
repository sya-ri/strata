package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:box
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext
import dev.s7a.strata.text.UiText

/**
 * Builds the Box panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.boxPanel(minecraft: MinecraftUiContext) {
    Box(
        modifier = Modifier.Empty.size(320, 180),
        contentAlignment = Alignment.Center,
    ) {
        element(minecraft.text(UiText.Literal("Box")))
        element(
            minecraft.pointerButton(
                UiText.Literal("Top start"),
                modifier = Modifier.Empty.align(Alignment.TopStart),
            ) {},
        )
        element(
            minecraft.pointerButton(
                UiText.Literal("Bottom end"),
                modifier = Modifier.Empty.align(Alignment.BottomEnd),
            ) {},
        )
    }
}
// showcase-source-end:box
