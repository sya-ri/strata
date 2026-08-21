package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:social-screen
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.BoxScope
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
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
            socialBackground(panel, searchIcon)
            socialHeader()
            socialTabs()
            socialSearch(search)
            socialPlayer(playerSkin)
            socialDone()
        }
    }
}

private fun BoxScope.socialBackground(
    panel: DrawImage,
    searchIcon: DrawImage,
) {
    Box(
        modifier =
            Modifier.Empty.padding(left = 44, top = 64).size(236, 112).imageBackground(
                panel,
                Insets.all(8),
                MinecraftNineSliceCenterMode.Tiled,
            ),
    ) {}
    Image(searchIcon, IntSize(12, 12), Modifier.Empty.padding(left = 54, top = 76))
}

private fun BoxScope.socialHeader() {
    Box(modifier = Modifier.Empty.size(320, 21), contentAlignment = Alignment.BottomCenter) {
        Text("Social Interactions")
    }
    Text("Player0 - New World - 1 player", modifier = Modifier.Empty.padding(left = 49, top = 35))
}

private fun BoxScope.socialTabs() {
    Row(modifier = Modifier.Empty.padding(left = 50, top = 45), spacing = 1) {
        Box {
            Button("All", width = 73, modifier = Modifier.Empty.onPress {})
            Spacer(
                modifier =
                    Modifier.Empty
                        .padding(left = 30, top = 15)
                        .size(13, 1)
                        .background(ArgbColor(0xFF3F3F3F.toInt())),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .padding(left = 29, top = 14)
                        .size(13, 1)
                        .background(ArgbColor(0xFFFFFFFF.toInt())),
            )
        }
        Button("Hidden", width = 73, modifier = Modifier.Empty.onPress {})
        Button("Blocked", width = 73, modifier = Modifier.Empty.onPress {})
    }
}

private fun BoxScope.socialSearch(search: MinecraftTextFieldState) {
    TextField(
        search,
        size = IntSize(200, 15),
        textStyle = MinecraftTextStyle.Normal,
        modifier = Modifier.Empty.padding(left = 69, top = 74).initialFocus(),
    )
}

private fun BoxScope.socialPlayer(playerSkin: DrawImage) {
    Box(
        modifier =
            Modifier.Empty
                .padding(left = 52, top = 92)
                .size(216, 32)
                .background(ArgbColor(0xFF4A4A4A.toInt())),
    ) {
        PlayerHead(playerSkin, modifier = Modifier.Empty.padding(left = 4, top = 4))
        Text("Player0", modifier = Modifier.Empty.padding(left = 32, top = 11))
    }
}

private fun BoxScope.socialDone() {
    Button("Done", width = 200, modifier = Modifier.Empty.padding(left = 60, top = 214).onPress {})
}
// showcase-source-end:social-screen
