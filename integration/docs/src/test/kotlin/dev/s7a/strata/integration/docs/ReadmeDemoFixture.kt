package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.nio.file.Path

/**
 * Hermetic original test assets for the offline demo players, without downloading game data.
 * The caller owns the temporary directory; every decoder is closed by the production asset loader.
 */
internal object ReadmeDemoFixture {
    /**
     * Adds synthetic skin sheets to the existing original-resource fixture and loads the real CPU rendering path.
     */
    fun assets(directory: Path): ReadmeDemoAssets {
        val fixture = ShowcaseMinecraftAssetFixture(directory)
        val thumb = ShowcaseGuiAsset.ScrollbarThumb
        fixture.replaceClient(
            "assets/${thumb.id.namespace}/${thumb.id.path}",
            ShowcaseFixturePng.create(thumb.size) { _, _ -> 0xFFD0D0D0.toInt() },
        )
        listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny").forEachIndexed { index, name ->
            fixture.replaceClient(
                "assets/minecraft/textures/entity/player/slim/$name.png",
                ShowcaseFixturePng.create(IntSize(64, 64)) { x, y ->
                    0xFF000000.toInt() or (((index * 25 + 50) and 255) shl 16) or ((x * 3) shl 8) or (y * 3)
                },
            )
        }
        val inputs = ShowcaseMinecraftInputs(fixture.clientJar, fixture.assetIndex, fixture.assetObjects, fixture.versionManifest, fixture.testResources)
        return ReadmeDemoAssets(inputs)
    }
}
