package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.docs.example.ReadmePlayer
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontBackend
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import java.awt.Font

/**
 * Loads immutable original Minecraft assets and fixed offline player data for a single demo run.
 * Construction is synchronous, closes every temporary decoder, and propagates invalid or missing input failures.
 * No Minecraft client, account lookup, GPU context, or mutable host is retained.
 */
internal class ReadmeDemoAssets(
    inputs: ShowcaseMinecraftInputs,
) {
    /**
     * Shared existing profile and asset loader; its component-showcase outputs are unaffected.
     */
    val minecraft: ShowcaseMinecraftAssets = ShowcaseMinecraftAssets(inputs, LwjglMinecraftFontBackendFactory)

    /**
     * Original Social Interactions panel, decoded once without a native client.
     */
    val panel: ImageSource = ImageSource.Pixels(minecraft.image(ShowcaseGuiAsset.SocialPanel.id))

    /**
     * Eight fixed presentation rows, using original default skin pixels in a deterministic order.
     */
    val players: List<ReadmePlayer> =
        LwjglMinecraftFontBackendFactory.open(inputs.compatibility).use { backend ->
            require(backend is MinecraftBoundedFontBackend) { "README skin decoding requires bounded allocation." }
            listOf(
                Triple("Alex", "Builder", "alex"),
                Triple("Ari", "Explorer", "ari"),
                Triple("Efe", "Miner", "efe"),
                Triple("Kai", "Farmer", "kai"),
                Triple("Makena", "Archer", "makena"),
                Triple("Noor", "Smith", "noor"),
                Triple("Steve", "Guard", "steve"),
                Triple("Sunny", "Scout", "sunny"),
            ).map { (name, role, skinName) ->
                val resource = ResourceId("minecraft", "textures/entity/player/slim/$skinName.png")
                val bytes = requireNotNull(inputs.read(resource)) { "Missing default player skin: $resource" }
                require(inputs.limits.checkPng(bytes)) { "Player skin must be an original PNG." }
                val skin = backend.decodePng(bytes, inputs.limits)
                require(skin.size == IntSize(64, 64)) { "Player skin must be 64 by 64." }
                ReadmePlayer(name, role, PlayerSkinSource.Pixels(skin))
            }
        }

    /**
     * Hashes of the actual consumed inputs, fenced after all skin and profile reads.
     */
    val hashes: Map<String, String> = inputs.inputHashes(players.joinToString { it.name + ":" + it.role })

    /**
     * Fixed embedded code typeface; independent of installed operating-system fonts.
     */
    companion object {
        /**
         * Reads a bundled font or license, closing the classpath stream and failing on missing resources.
         */
        fun resource(name: String): ByteArray =
            requireNotNull(ReadmeDemoAssets::class.java.getResourceAsStream("/readme-demo/$name")) {
                "Missing bundled README demo resource: $name"
            }.use { it.readBytes() }

        /**
         * Creates a caller-owned font from the pinned resource with deterministic point sizing.
         */
        fun font(): Font = resource("JetBrainsMono-Regular.ttf").inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }
}
