package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontGpuImageComparison
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityChecks
import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontEvidence
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontFixture
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extends the existing three native/offline comparison tasks with exact numeric metrics and signed widths.
 * A fresh independent CPU render must match the earlier CPU worker and loaded Fabric images exactly.
 * Native GPU differences require the unchanged per-pixel float proof and current format calibration when applicable.
 */
@Tag("font-offline-parity")
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftOfflineNumericFontParityTest {
    @Test
    fun `independent offline numeric fonts match native metrics widths and verified rendering`() {
        val native = Path.of(MinecraftNumericFontTestContract.property("strata.fontNativeOutput")).resolve("numeric")
        val offline = Path.of(MinecraftNumericFontTestContract.property("strata.fontOfflineOutput")).resolve("numeric")
        val output = Path.of(MinecraftNumericFontTestContract.property("strata.fontComparisonOutput")).resolve("numeric")
        Files.createDirectories(output)
        val receipt = output.resolve("numeric-offline-parity.properties")
        Files.deleteIfExists(receipt)
        MinecraftNumericFontEvidence.verifyReceipt(native)
        val compatibility = MinecraftFontParityFixture.target.compatibility
        val fresh = MinecraftNumericFontEvidence.portable(compatibility)
        MinecraftNumericFontEvidence.verify(MinecraftNumericFontEvidence.read(native.resolve("numeric-native.properties")), fresh)
        MinecraftNumericFontEvidence.verify(MinecraftNumericFontEvidence.read(offline.resolve("numeric-offline.properties")), fresh)
        val manifest = MinecraftNumericFontEvidence.read(offline.resolve("numeric-offline-images.properties"))
        assertEquals(MinecraftNumericFontEvidence.hash(offline.resolve("numeric-offline.properties")), manifest["observations.sha256"])
        val profile = MinecraftFontParityFixture.profile(MinecraftNumericFontFixture.snapshot(compatibility))
        val evidence =
            linkedMapOf(
                "minecraft.version" to MinecraftFontParityFixture.target.version,
                "native.receipt.sha256" to MinecraftNumericFontEvidence.hash(native.resolve("numeric-parity.properties")),
                "offline.observations.sha256" to MinecraftNumericFontEvidence.hash(offline.resolve("numeric-offline.properties")),
                "offline.images.sha256" to MinecraftNumericFontEvidence.hash(offline.resolve("numeric-offline-images.properties")),
            )
        for (scale in 1..3) {
            val worker = offline.resolve("font-offline-$scale.png")
            assertEquals(MinecraftNumericFontEvidence.hash(worker), manifest["image.${worker.fileName}.sha256"])
            compareScale(native, worker, output, profile, scale)
            evidence["proof.$scale.sha256"] = MinecraftNumericFontEvidence.hash(output.resolve("font-native-$scale-comparison.properties"))
        }
        MinecraftNumericFontEvidence.write(receipt, evidence)
    }

    private fun compareScale(
        native: Path,
        worker: Path,
        output: Path,
        profile: MinecraftUiProfile,
        scale: Int,
    ) {
        val portable = MinecraftNumericFontFixture.render(profile, scale)
        val freshPath = output.resolve("font-headless-$scale.png")
        MinecraftFontParityChecks.verifyPixels(worker, portable, freshPath)
        val comparison =
            MinecraftFontGpuImageComparison.compare(
                native.resolve("font-native-$scale.png"),
                portable,
                freshPath,
                MinecraftNumericFontFixture.commands(profile),
                scale,
                output,
            )
        assertEquals(0, comparison.unverified, comparison.firstFailure)
        for (kind in listOf("headless", "fabric")) MinecraftFontParityChecks.verifyPixels(native.resolve("font-$kind-$scale.png"), portable, freshPath)
    }
}
