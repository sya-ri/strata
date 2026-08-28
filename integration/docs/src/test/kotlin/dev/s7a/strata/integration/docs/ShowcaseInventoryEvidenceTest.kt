package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the explicit native inventory exception without a game, implicit cache, or renderer.
 */
internal class ShowcaseInventoryEvidenceTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun nativeInventoryProofBindsNormalizedCurrentSourceAndDetachedImage() {
        val image = png(320, 240)
        val source = "import sample\r\ninternal fun inventory() {}"
        val imagePath = temporaryRoot.resolve("inventory.png")
        val proofPath = temporaryRoot.resolve("inventory.properties")
        Files.write(imagePath, image)
        Files.write(proofPath, MinecraftShowcaseParityChecker.inventoryReceipt(image, source))

        val evidence = ShowcaseInventoryEvidence.load(imagePath, proofPath, source.replace("\r\n", "\n"))
        val returned = evidence.png()
        returned[0] = 0
        Files.write(imagePath, byteArrayOf(0))

        assertArrayEquals(image, evidence.png())
        assertEquals(ShowcaseFrameReceipt.Origin.LoadedServerFabric, evidence.frame.origin)
        assertEquals(1, evidence.frame.viewport.scale)
        assertEquals(320, evidence.frame.viewport.physicalSize.width)
        assertEquals(240, evidence.frame.viewport.physicalSize.height)
        assertEquals(ShowcaseFrameReceipt.sha256(Files.readAllBytes(proofPath)), evidence.proofSha256)
    }

    @Test
    fun nativeInventoryRejectsWrongVersionHashesAndReceiptSchema() {
        val image = png(320, 240)
        val imagePath = temporaryRoot.resolve("inventory.png")
        val proofPath = temporaryRoot.resolve("inventory.properties")
        val source = "import sample\ninternal fun inventory() {}"
        val valid = MinecraftShowcaseParityChecker.inventoryReceipt(image, source).toString(Charsets.UTF_8)
        Files.write(imagePath, image)
        val imageHash = ShowcaseFrameReceipt.sha256(image)
        listOf(
            valid.replace("minecraft.version=26.2", "minecraft.version=26.1"),
            valid.replace("png.sha256=$imageHash", "png.sha256=${"0".repeat(64)}"),
            valid.replace("png.sha256=$imageHash\n", ""),
            valid + "other=value\n",
            valid + "minecraft.version=26.2\n",
            valid + "\n",
        ).forEach { proof ->
            Files.writeString(proofPath, proof)
            assertThrows(IllegalArgumentException::class.java) { ShowcaseInventoryEvidence.load(imagePath, proofPath, source) }
        }
        Files.writeString(proofPath, valid)
        assertThrows(IllegalArgumentException::class.java) { ShowcaseInventoryEvidence.load(imagePath, proofPath, "$source\n") }
        Files.write(proofPath, byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseInventoryEvidence.load(imagePath, proofPath, source) }
    }

    @Test
    fun nativeInventoryRequiresExactPhysicalDimensionsEvenWithMatchingProof() {
        val source = "source"
        val imagePath = temporaryRoot.resolve("inventory.png")
        val proofPath = temporaryRoot.resolve("inventory.properties")
        listOf(png(319, 240), png(320, 0), png(640, 480)).forEach { image ->
            Files.write(imagePath, image)
            Files.write(proofPath, MinecraftShowcaseParityChecker.inventoryReceipt(image, source))
            assertThrows(IllegalArgumentException::class.java) { ShowcaseInventoryEvidence.load(imagePath, proofPath, source) }
        }
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(24)
            .apply {
                put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                position(16)
                putInt(width)
                putInt(height)
            }.array()
}
