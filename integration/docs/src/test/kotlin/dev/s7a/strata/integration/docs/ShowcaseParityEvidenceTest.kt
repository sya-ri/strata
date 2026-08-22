package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            assertEquals(scenario.viewport, evidence.componentViewport(scenario.component))
            assertArrayEquals(
                png(scenario.viewport.width, scenario.viewport.height),
                evidence.componentPng(scenario.component),
            )
        }
        assertArrayEquals(png(320, 240), evidence.screenPng(DocumentedScreen.SocialInteractions))
        assertArrayEquals(png(320, 240), evidence.screenPng(DocumentedScreen.SynchronizedInventory))
        assertArrayEquals(png(320, 180), evidence.screenPng(DocumentedScreen.IndustrialController))
        assertArrayEquals(png(320, 180), evidence.screenPng(DocumentedScreen.PowerMilestones))
        val receipt = evidence.receipt()
        receipt[0] = 0
        assertArrayEquals(Files.readAllBytes(temporaryRoot.resolve("receipt.properties")), evidence.receipt())
    }

    @Test
    fun staleHashAndWrongDimensionsAreRejected() {
        val buttonScenario = componentScenario(DocumentedComponent.Button)
        writeEvidence()
        Files.write(
            temporaryRoot.resolve("components/button.png"),
            png(buttonScenario.viewport.width - 1, buttonScenario.viewport.height),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence()
        val receipt = Files.readString(temporaryRoot.resolve("receipt.properties"))
        val buttonPng = png(buttonScenario.viewport.width, buttonScenario.viewport.height)
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            receipt.replace("component.button.png.sha256=${sha256(buttonPng)}", "component.button.png.sha256=${"0".repeat(64)}"),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    @Test
    fun invalidComponentViewportAndFabricHeadlessHashAreRejected() {
        writeEvidence()
        val receiptPath = temporaryRoot.resolve("receipt.properties")
        val receipt = Files.readString(receiptPath)
        Files.writeString(
            receiptPath,
            receipt.replace("component.button.viewport.width=${componentScenario(DocumentedComponent.Button).viewport.width}", "component.button.viewport.width=0"),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }

        writeEvidence()
        Files.writeString(
            receiptPath,
            Files.readString(receiptPath).replace(
                "component.button.fabric.headless.argb.sha256=${componentArgbHash(DocumentedComponent.Button)}",
                "component.button.fabric.headless.argb.sha256=invalid",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    @Test
    fun pipelineRejectsCatalogViewportThatDiffersFromReceipt() {
        val scenario = componentScenario(DocumentedComponent.Button)
        val recordedViewport = IntSize(scenario.viewport.width - 1, scenario.viewport.height)
        writeEvidence(mapOf(scenario.component to recordedViewport))

        val evidence = ShowcaseParityEvidence.load(temporaryRoot)

        assertEquals(recordedViewport, evidence.componentViewport(scenario.component))
        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePipeline.verifyComponentViewport(scenario, evidence)
        }
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

    private fun writeEvidence(viewportOverrides: Map<DocumentedComponent, IntSize> = emptyMap()) {
        val components = temporaryRoot.resolve("components")
        Files.createDirectories(components)
        val screens = temporaryRoot.resolve("screens")
        Files.createDirectories(screens)
        val overview = png(320, 180)
        Files.write(components.resolve("overview.png"), overview)
        val componentViewports =
            ShowcaseScenarioCatalog.components.associate { scenario ->
                scenario.component to (viewportOverrides[scenario.component] ?: scenario.viewport)
            }
        val componentImages =
            componentViewports.mapValues { (_, viewport) ->
                png(viewport.width, viewport.height)
            }
        componentImages.forEach { (component, bytes) -> Files.write(components.resolve("${component.slug}.png"), bytes) }
        val screenImages =
            linkedMapOf(
                DocumentedScreen.SocialInteractions.slug to png(320, 240),
                DocumentedScreen.SynchronizedInventory.slug to png(320, 240),
                DocumentedScreen.IndustrialController.slug to png(320, 180),
                DocumentedScreen.PowerMilestones.slug to png(320, 180),
            )
        screenImages.forEach { (slug, bytes) -> Files.write(screens.resolve("$slug.png"), bytes) }
        val receipt =
            buildString {
                appendLine("minecraft.version=26.2")
                appendLine("viewport.width=320")
                appendLine("viewport.height=180")
                appendLine("gui.scale=1")
                appendLine("locale=en_us")
                appendLine("native.fabric.headless.argb.sha256=${"1".repeat(64)}")
                appendLine("native.fabric.headless.scroll.argb.sha256=${"2".repeat(64)}")
                appendLine("native.fabric.headless.direct-join.argb.sha256=${"3".repeat(64)}")
                appendLine("native.fabric.headless.container-background.argb.sha256=${"4".repeat(64)}")
                appendLine("native.fabric.headless.slot.argb.sha256=${"5".repeat(64)}")
                appendLine("fabric.headless.industrial.argb.sha256=${"6".repeat(64)}")
                appendLine("native.fabric.headless.player-head.argb.sha256=${"7".repeat(64)}")
                appendLine("native.fabric.headless.social.argb.sha256=${"8".repeat(64)}")
                appendLine("fabric.headless.progress.argb.sha256=${"9".repeat(64)}")
                appendLine("component.overview.png.sha256=${sha256(overview)}")
                componentImages.forEach { (component, bytes) ->
                    val viewport = componentViewports.getValue(component)
                    appendLine("component.${component.slug}.viewport.width=${viewport.width}")
                    appendLine("component.${component.slug}.viewport.height=${viewport.height}")
                    appendLine("component.${component.slug}.fabric.headless.argb.sha256=${componentArgbHash(component)}")
                    appendLine("component.${component.slug}.png.sha256=${sha256(bytes)}")
                }
                screenImages.forEach { (slug, bytes) -> appendLine("screen.$slug.png.sha256=${sha256(bytes)}") }
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

    private fun componentScenario(component: DocumentedComponent): ComponentScenario = ShowcaseScenarioCatalog.components.single { scenario -> scenario.component == component }

    private fun componentArgbHash(component: DocumentedComponent): String {
        val digit = (component.ordinal + 10).toString(16).last().toString()
        return digit.repeat(64)
    }
}
