package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontEvidence
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontFixture
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates raw numeric observations and fresh scene images in the existing isolated CPU-only workers.
 * Original fixture bytes are the only font input; no native game class, measurement, screenshot, or graphics context is consulted.
 * The owned image manifest is emitted only after every required physical density renders successfully.
 */
@Tag("font-offline-scene")
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftOfflineNumericFontSceneTest {
    @Test
    fun `numeric pack inputs produce independent raw observations and finite viewport images`() {
        val output = Path.of(MinecraftNumericFontTestContract.property("strata.fontOfflineOutput")).resolve("numeric")
        Files.createDirectories(output)
        val observations = output.resolve("numeric-offline.properties")
        val manifest = output.resolve("numeric-offline-images.properties")
        Files.deleteIfExists(observations)
        Files.deleteIfExists(manifest)
        val compatibility = MinecraftNumericFontTestContract.compatibility()
        MinecraftNumericFontEvidence.write(observations, MinecraftNumericFontEvidence.portable(compatibility))
        val profile = MinecraftFontParityFixture.profile(MinecraftNumericFontFixture.snapshot(compatibility))
        val images = linkedMapOf("observations.sha256" to MinecraftNumericFontEvidence.hash(observations))
        for (scale in 1..3) {
            val image = MinecraftNumericFontFixture.render(profile, scale)
            assertEquals(MinecraftFontParityFixture.viewport.width * scale, image.size.width)
            assertEquals(MinecraftFontParityFixture.viewport.height * scale, image.size.height)
            assertTrue(image.copyArgb().any { it != MinecraftFontParityFixture.background }, "The ordinary prefix must remain visible in the numeric scene.")
            val path = output.resolve("font-offline-$scale.png")
            Files.write(path, image.encodePng())
            images["image.${path.fileName}.sha256"] = MinecraftNumericFontEvidence.hash(path)
        }
        MinecraftNumericFontEvidence.write(manifest, images)
    }
}
