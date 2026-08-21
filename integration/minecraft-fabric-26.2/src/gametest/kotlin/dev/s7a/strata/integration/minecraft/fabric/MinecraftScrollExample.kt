package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:scroll
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds the deterministic Minecraft 26.2 selection-list screen used by the native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native list viewport, row geometry, separators, scrollbar, and text.
 */
internal fun createScrollScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Strata Scroll parity") {
        buildUi {
            Box(modifier = Modifier.Empty.size(320, 180)) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
                Column(modifier = Modifier.Empty.size(320, 180)) {
                    Spacer(modifier = Modifier.Empty.size(320, 33))
                    Scroll(modifier = Modifier.Empty.size(320, 94)) {
                        Column(
                            modifier = Modifier.Empty.size(270, 216),
                            horizontalAlignment = HorizontalAlignment.Center,
                        ) {
                            listOf(
                                "Entry 01",
                                "Entry 02",
                                "Entry 03",
                                "Entry 04",
                                "Entry 05",
                                "Entry 06",
                                "Entry 07",
                                "Entry 08",
                                "Entry 09",
                                "Entry 10",
                                "Entry 11",
                                "Entry 12",
                            ).forEach { label ->
                                Box(
                                    modifier = Modifier.Empty.size(270, 18),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    Column(horizontalAlignment = HorizontalAlignment.Center) {
                                        Spacer(modifier = Modifier.Empty.size(0, 5))
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.Empty.size(320, 53))
                }
            }
        }
    }
// showcase-source-end:scroll
