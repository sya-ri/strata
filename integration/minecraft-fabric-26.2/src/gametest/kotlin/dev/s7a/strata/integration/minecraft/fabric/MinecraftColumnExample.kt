package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:column
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds the Column panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.columnPanel(minecraft: MinecraftUiContext) {
    Column(
        modifier = Modifier.Empty.size(320, 180),
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Spacer(modifier = Modifier.Empty.height(20))
        with(minecraft) { Text("Column") }
        Spacer(modifier = Modifier.Empty.height(11))
        with(minecraft) { Button("Enabled") }
        Spacer(modifier = Modifier.Empty.height(4))
        with(minecraft) { Button("Disabled", enabled = false) }
    }
}
// showcase-source-end:column
