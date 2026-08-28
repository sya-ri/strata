@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path
import java.util.Collections

/**
 * Owns immutable original-resource images, fonts, and input hashes for one headless showcase generation.
 * Construction synchronously validates every input and closes its temporary CPU decoder, including after failure.
 * The result owns no Minecraft class, window, graphics context, source, stream, or native face and may be shared read-only.
 * The primary constructor accepts a bounded backend factory for independent pure tests; production uses the five-path constructor.
 */
internal class ShowcaseMinecraftAssets(
    inputs: ShowcaseMinecraftInputs,
    backendFactory: MinecraftFontBackendFactory,
) {
    private val images: Map<ShowcaseGuiAsset, DrawImage>

    /**
     * Complete Minecraft profile assembled from the caller's exact original assets and fixed font options.
     */
    val profile: MinecraftUiProfile

    private val hashes: Map<String, String>

    /**
     * Explicit deterministic offline player identity used by documentation scenes.
     */
    val playerName: String = "Player0"

    /**
     * Fixed original Efe skin, shared with native scene fixtures rather than obtained from a network account.
     */
    val playerSkin: PlayerSkinSource

    /**
     * Loads explicitly supplied official files and repository fixtures with the target CPU font backend.
     * The version manifest must identify 26.2 and exactly hash both the client archive and asset index.
     * The caller must keep all five input locations stable until construction returns; no implicit cache path is consulted.
     */
    constructor(clientJar: Path, assetIndex: Path, assetObjects: Path, versionManifest: Path, testResources: Path) : this(
        ShowcaseMinecraftInputs(clientJar, assetIndex, assetObjects, versionManifest, testResources),
        LwjglMinecraftFontBackendFactory,
    )

    init {
        val fonts = inputs.fonts()
        images =
            backendFactory.open(inputs.compatibility).use { backend ->
                require(backend is MinecraftBoundedFontBackend) { "Showcase image decoding requires a bounded CPU backend." }
                Collections.unmodifiableMap(
                    ShowcaseGuiAsset.entries.associateWith { asset ->
                        val bytes = requireNotNull(inputs.read(asset.id)) { "Missing original showcase resource: " + asset.id }
                        require(inputs.limits.checkPng(bytes)) { "Showcase images must be original PNG resources." }
                        val image = backend.decodePng(bytes, inputs.limits)
                        inputs.limits.requireImageSize(image.size.width, image.size.height)
                        require(image.size == asset.size) { "Showcase resource has unexpected dimensions: " + asset.id }
                        asset.metadata.validate(inputs.read(asset.id, metadata = true), image.size, inputs.limits)
                        image
                    },
                )
            }
        profile = createShowcaseMinecraftProfile(fonts, images)
        playerSkin = PlayerSkinSource.Pixels(images.getValue(ShowcaseGuiAsset.PlayerSkin))
        hashes = inputs.inputHashes(playerName)
    }

    /**
     * Returns one eagerly loaded immutable scene image; unknown resources fail instead of performing late file access.
     */
    fun image(id: ResourceId): DrawImage {
        val key = requireNotNull(ShowcaseGuiAsset.entries.singleOrNull { asset -> asset.id == id }) { "Resource is outside the fixed showcase asset inventory: $id" }
        return images.getValue(key)
    }

    /**
     * Returns stable logical keys and lowercase SHA-256 values for every consumed source and selected configuration.
     * No absolute filesystem path or native screenshot appears in this immutable map.
     */
    fun inputHashes(): Map<String, String> = hashes
}
