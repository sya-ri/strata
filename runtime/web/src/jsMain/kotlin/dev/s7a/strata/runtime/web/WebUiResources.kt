package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.state.StateSource

/**
 * Immutable browser resource snapshot borrowed by mounted hosts.
 * Prepare images and fonts before mounting; missing identifiers fail explicitly.
 * The caller owns loaded fonts and skin sources and closes them after all borrowing hosts.
 * Skin lookup must return a stable source for each live lookup; the shared retained engine owns subscriptions.
 * No Minecraft assets, account credentials, resource-pack files or network policy are bundled.
 */
public class WebUiResources(
    images: Map<ResourceId, DrawImage> = emptyMap(),
    fonts: Map<ResourceId, WebFont> = emptyMap(),
    public val defaultFont: WebFont? = null,
    public val skins: ((PlayerSkinSource) -> StateSource<WebPlayerSkin>)? = null,
) {
    internal val images = images.toMap()
    internal val fonts = fonts.toMap()
}
