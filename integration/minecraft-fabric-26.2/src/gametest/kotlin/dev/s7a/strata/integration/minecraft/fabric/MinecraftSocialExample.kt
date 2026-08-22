package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:social-screen
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftNineSliceCenterMode
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.PlayerHead
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.TextField
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.imageBackground
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the deterministic one-player Minecraft 26.2 Social Interactions screen from general-purpose primitives.
 *
 * Social-entry composition remains application code: the public runtime supplies PlayerHead, text, actions, images, fields, layout, and active backgrounds without exposing a purpose-specific SocialEntry component.
 *
 * @param panel exact active-resource `social_interactions/background` pixels.
 * @param searchIcon exact active-resource `icon/search` pixels.
 * @param playerSkin detached selected-player skin pixels.
 * @return one-shot screen definition reproducing the native screen geometry and draw order.
 */
internal fun createSocialScreenDefinition(
    panel: DrawImage,
    searchIcon: DrawImage,
    playerSkin: DrawImage,
): MinecraftScreenDefinition {
    val search = createMinecraftTextFieldState("", maxLength = 16)
    return createMinecraftScreenDefinition("Social Interactions") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Box(
                modifier = Modifier.Empty.size(320, 176),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.Empty
                            .padding(left = 4)
                            .size(236, 112)
                            .imageBackground(
                                panel,
                                Insets.all(8),
                                MinecraftNineSliceCenterMode.Tiled,
                            ),
                ) {}
            }
            Column(
                modifier = Modifier.Empty.size(222, 234).align(Alignment.TopCenter),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.Empty.padding(top = 12)) {
                    Column(
                        modifier = Modifier.Empty.size(222, 32),
                        spacing = 14,
                    ) {
                        Text(
                            "Social Interactions",
                            modifier = Modifier.Empty.align(HorizontalAlignment.Center),
                        )
                        Text("Player0 - New World - 1 player")
                    }
                    Row(modifier = Modifier.Empty.padding(left = 1, top = 1), spacing = 1) {
                        Box(modifier = Modifier.Empty.size(73, 20)) {
                            Button("All", width = 73, modifier = Modifier.Empty.onPress {})
                            Box(
                                modifier = Modifier.Empty.size(73, 16),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier =
                                        Modifier.Empty
                                            .size(13, 1)
                                            .background(ArgbColor(0xFF3F3F3F.toInt())),
                                ) {}
                            }
                            Box(
                                modifier = Modifier.Empty.size(72, 15),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier =
                                        Modifier.Empty
                                            .size(13, 1)
                                            .background(ArgbColor(0xFFFFFFFF.toInt())),
                                ) {}
                            }
                        }
                        Button("Hidden", width = 73, modifier = Modifier.Empty.onPress {})
                        Button("Blocked", width = 73, modifier = Modifier.Empty.onPress {})
                    }
                    Row(
                        modifier = Modifier.Empty.padding(left = 5, top = 9),
                        spacing = 3,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        Image(
                            searchIcon,
                            IntSize(12, 12),
                            modifier = Modifier.Empty.padding(top = 2),
                        )
                        TextField(
                            search,
                            size = IntSize(200, 15),
                            textStyle = MinecraftTextStyle.Normal,
                            modifier = Modifier.Empty.initialFocus(),
                        )
                    }
                    Row(
                        modifier =
                            Modifier.Empty
                                .padding(left = 3, top = 3)
                                .size(216, 32)
                                .background(ArgbColor(0xFF4A4A4A.toInt())),
                        spacing = 4,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        PlayerHead(playerSkin, modifier = Modifier.Empty.padding(left = 4))
                        Text("Player0")
                    }
                }
                Button(
                    "Done",
                    width = 200,
                    modifier = Modifier.Empty.align(HorizontalAlignment.Center).onPress {},
                )
            }
        }
    }
}
// showcase-source-end:social-screen
