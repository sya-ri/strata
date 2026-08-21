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
        writeEvidence(png)

        val evidence = ShowcaseParityEvidence.load(temporaryRoot)
        val first = evidence.overviewPng()
        first[0] = 0

        assertArrayEquals(png, evidence.overviewPng())
        DocumentedComponent.entries.forEach { component -> assertArrayEquals(png, evidence.componentPng(component)) }
        val receipt = evidence.receipt()
        receipt[0] = 0
        assertArrayEquals(Files.readAllBytes(temporaryRoot.resolve("receipt.properties")), evidence.receipt())
    }

    @Test
    fun staleHashAndWrongDimensionsAreRejected() {
        val png = png(320, 180)
        writeEvidence(png)
        Files.write(temporaryRoot.resolve("components/row.png"), png(319, 180))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence(png)
        val receipt = Files.readString(temporaryRoot.resolve("receipt.properties"))
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            receipt.replace("component.row.png.sha256=${sha256(png)}", "component.row.png.sha256=${"0".repeat(64)}"),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    @Test
    fun malformedUtf8AndExtraTerminalLineAreRejected() {
        val png = png(320, 180)
        writeEvidence(png)
        Files.write(temporaryRoot.resolve("receipt.properties"), byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence(png)
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            Files.readString(temporaryRoot.resolve("receipt.properties")) + "\n",
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    private fun writeEvidence(png: ByteArray) {
        val components = temporaryRoot.resolve("components")
        Files.createDirectories(components)
        val slugs = listOf("overview") + DocumentedComponent.entries.map { component -> component.slug }
        slugs.forEach { slug -> Files.write(components.resolve("$slug.png"), png) }
        val hash = sha256(png)
        val receipt =
            buildString {
                appendLine("minecraft.version=26.2")
                appendLine("viewport.width=640")
                appendLine("viewport.height=540")
                appendLine("gui.scale=1")
                appendLine("locale=en_us")
                appendLine("native.fabric.headless.argb.sha256=${"1".repeat(64)}")
                slugs.forEach { slug -> appendLine("component.$slug.png.sha256=$hash") }
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
