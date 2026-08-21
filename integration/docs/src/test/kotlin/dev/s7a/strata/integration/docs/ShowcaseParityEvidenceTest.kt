package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Verifies that generated documentation accepts only exact Minecraft parity evidence.
 */
internal class ShowcaseParityEvidenceTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun validEvidenceIsDetachedFromEveryReturnedArray() {
        val png = png(320, 180)
        writeEvidence()

        val evidence = ShowcaseParityEvidence.load(temporaryRoot)
        val first = evidence.overviewPng()
        first[0] = 0

        assertArrayEquals(png, evidence.overviewPng())
        assertArrayEquals(png(32, 32), evidence.componentPng(DocumentedComponent.MenuBackground))
        assertArrayEquals(png(150, 20), evidence.componentPng(DocumentedComponent.Text))
        assertArrayEquals(png(150, 20), evidence.componentPng(DocumentedComponent.Button))
        assertArrayEquals(png(320, 94), evidence.componentPng(DocumentedComponent.Scroll))
        val receipt = evidence.receipt()
        receipt[0] = 0
        assertArrayEquals(Files.readAllBytes(temporaryRoot.resolve("receipt.properties")), evidence.receipt())
    }

    @Test
    fun staleHashAndWrongDimensionsAreRejected() {
        writeEvidence()
        Files.write(temporaryRoot.resolve("components/button.png"), png(149, 20))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence()
        val receipt = Files.readString(temporaryRoot.resolve("receipt.properties"))
        val buttonPng = png(150, 20)
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            receipt.replace("component.button.png.sha256=${sha256(buttonPng)}", "component.button.png.sha256=${"0".repeat(64)}"),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    @Test
    fun malformedUtf8AndExtraTerminalLineAreRejected() {
        writeEvidence()
        Files.write(temporaryRoot.resolve("receipt.properties"), byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence()
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            Files.readString(temporaryRoot.resolve("receipt.properties")) + "\n",
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    private fun writeEvidence() {
        val components = temporaryRoot.resolve("components")
        Files.createDirectories(components)
        val images =
            linkedMapOf(
                "overview" to png(320, 180),
                DocumentedComponent.MenuBackground.slug to png(32, 32),
                DocumentedComponent.Text.slug to png(150, 20),
                DocumentedComponent.Button.slug to png(150, 20),
                DocumentedComponent.Scroll.slug to png(320, 94),
            )
        images.forEach { (slug, bytes) -> Files.write(components.resolve("$slug.png"), bytes) }
        val receipt =
            buildString {
                appendLine("minecraft.version=26.2")
                appendLine("viewport.width=320")
                appendLine("viewport.height=180")
                appendLine("gui.scale=1")
                appendLine("locale=en_us")
                appendLine("native.fabric.headless.argb.sha256=${"1".repeat(64)}")
                appendLine("native.fabric.headless.scroll.argb.sha256=${"2".repeat(64)}")
                images.forEach { (slug, bytes) -> appendLine("component.$slug.png.sha256=${sha256(bytes)}") }
            }
        Files.writeString(temporaryRoot.resolve("receipt.properties"), receipt)
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

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
