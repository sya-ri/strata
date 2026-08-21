package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:image
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.imageBackground

/**
 * Builds a reusable industrial Mod screen from general-purpose Strata primitives and one replaceable resource-pack asset.
 *
 * @param panel immutable panel pixels loaded by the version adapter from the active resource manager.
 * @return one-shot definition containing image, text, slot, layout, gauge composition, and button primitives.
 */
internal fun createIndustrialScreenDefinition(panel: DrawImage): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Industrial controller") {
        Box(
            modifier = Modifier.Empty.size(320, 180).imageBackground(panel),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 8,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Image(panel, IntSize(32, 32))
                Text("ENERGY CONTROL")
                Box(
                    modifier = Modifier.Empty.size(150, 8).background(ArgbColor(0xFF101820.toInt())),
                ) {
                    Spacer(modifier = Modifier.Empty.size(112, 8).background(ArgbColor(0xFF22D3EE.toInt())))
                }
                Row(spacing = 4) {
                    machineSlot(ArgbColor(0xFFFBBF24.toInt()))
                    machineSlot(ArgbColor(0xFF22D3EE.toInt()))
                    machineSlot(ArgbColor(0xFFA78BFA.toInt()))
                }
                Button(
                    "Toggle power",
                    width = 100,
                    modifier = Modifier.Empty.onPress {},
                )
            }
        }
    }

private fun UiScope.machineSlot(color: ArgbColor) {
    Slot {
        Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
    }
}
// showcase-source-end:image
