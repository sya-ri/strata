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
            val recordedViewport = evidence.componentViewport(scenario.component)
            assertEquals(scenario.viewport, recordedViewport.size)
            assertEquals(scenario.scale, recordedViewport.scale)
            ShowcasePipeline.verifyComponentViewport(scenario, evidence)
            assertArrayEquals(
                png(recordedViewport.physicalSize.width, recordedViewport.physicalSize.height),
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

        assertEquals(recordedViewport, evidence.componentViewport(scenario.component).size)
        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePipeline.verifyComponentViewport(scenario, evidence)
        }
    }

    @Test
    fun componentScaleFieldIsRequiredByTheExactReceiptSchema() {
        writeEvidence()
        val receiptPath = temporaryRoot.resolve("receipt.properties")
        val receipt = Files.readString(receiptPath)
        listOf("", "component.text.scale=2\n").forEach { replacement ->
            Files.writeString(receiptPath, receipt.replace("component.text.gui.scale=2\n", replacement))
            assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
        }
    }

    @Test
    fun nonPositiveAndMalformedComponentScalesAreRejected() {
        writeEvidence()
        val receiptPath = temporaryRoot.resolve("receipt.properties")
        val receipt = Files.readString(receiptPath)
        listOf("0", "-1", "invalid", "2147483648").forEach { scale ->
            Files.writeString(receiptPath, receipt.replace("component.text.gui.scale=2", "component.text.gui.scale=$scale"))
            assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
        }
    }

    @Test
    fun componentPhysicalDimensionsUseCheckedMultiplicationOnBothAxes() {
        writeEvidence()
        val receiptPath = temporaryRoot.resolve("receipt.properties")
        val receipt = Files.readString(receiptPath)
        val viewport = componentScenario(DocumentedComponent.Text).viewport
        mapOf("width" to viewport.width, "height" to viewport.height).forEach { (axis, dimension) ->
            Files.writeString(
                receiptPath,
                receipt.replace("component.text.viewport.$axis=$dimension", "component.text.viewport.$axis=${Int.MAX_VALUE}"),
            )
            assertThrows(ArithmeticException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
        }
    }

    @Test
    fun scaleTwoReceiptRejectsAnUnscaledPngEvenWhenItsHashMatches() {
        writeEvidence(scaleOverrides = mapOf(DocumentedComponent.Text to 1))
        val receiptPath = temporaryRoot.resolve("receipt.properties")
        Files.writeString(
            receiptPath,
            Files.readString(receiptPath).replace("component.text.gui.scale=1", "component.text.gui.scale=2"),
        )

        assertThrows(IllegalArgumentException::class.java) { ShowcaseParityEvidence.load(temporaryRoot) }
    }

    @Test
    fun pipelineRejectsConsistentButStaleScaleOneTextEvidence() {
        val scenario = componentScenario(DocumentedComponent.Text)
        writeEvidence(scaleOverrides = mapOf(scenario.component to 1))
        val evidence = ShowcaseParityEvidence.load(temporaryRoot)

        assertEquals(scenario.viewport, evidence.componentViewport(scenario.component).size)
        assertEquals(1, evidence.componentViewport(scenario.component).scale)
        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePipeline.verifyComponentViewport(scenario, evidence)
        }
        val headless = writeHeadlessFrames(evidence)
        assertThrows(IllegalArgumentException::class.java) { MinecraftShowcaseParityChecker.verifyFrames(headless, evidence) }
    }

    @Test
    fun independentNativeCheckerAcceptsTheExactCompleteFrameSet() {
        writeEvidence()
        val evidence = ShowcaseParityEvidence.load(temporaryRoot)

        MinecraftShowcaseParityChecker.verifyFrames(writeHeadlessFrames(evidence), evidence)
    }

    @Test
    fun independentNativeCheckerRejectsChangedHeadlessFrames() {
        writeEvidence()
        val evidence = ShowcaseParityEvidence.load(temporaryRoot)
        val headless = writeHeadlessFrames(evidence)
        listOf("overview.png", "text.png", "screen-inventory.png").forEach { name ->
            val path = headless.resolve(name)
            val original = Files.readAllBytes(path)
            Files.write(path, original.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() })
            assertThrows(IllegalArgumentException::class.java) { MinecraftShowcaseParityChecker.verifyFrames(headless, evidence) }
            Files.write(path, original)
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

    private fun writeEvidence(
        viewportOverrides: Map<DocumentedComponent, IntSize> = emptyMap(),
        scaleOverrides: Map<DocumentedComponent, Int> = emptyMap(),
    ) {
        val components = temporaryRoot.resolve("components")
        Files.createDirectories(components)
        val screens = temporaryRoot.resolve("screens")
        Files.createDirectories(screens)
        val overview = png(320, 180)
        Files.write(components.resolve("overview.png"), overview)
        val componentViewports =
            ShowcaseScenarioCatalog.components.associate { scenario ->
                scenario.component to
                    ShowcaseViewport(
                        viewportOverrides[scenario.component] ?: scenario.viewport,
                        scaleOverrides[scenario.component] ?: scenario.scale,
                    )
            }
        val componentImages =
            componentViewports.mapValues { (_, viewport) ->
                png(viewport.physicalSize.width, viewport.physicalSize.height)
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
        Files.writeString(
            temporaryRoot.resolve("receipt.properties"),
            evidenceReceipt(overview, componentViewports, componentImages, screenImages),
        )
    }

    private fun writeHeadlessFrames(evidence: ShowcaseParityEvidence): Path {
        val components = temporaryRoot.resolve("headless/components")
        Files.createDirectories(components)
        Files.write(components.resolve("overview.png"), evidence.overviewPng())
        DocumentedComponent.entries.forEach { component -> Files.write(components.resolve("${component.slug}.png"), evidence.componentPng(component)) }
        DocumentedScreen.entries.forEach { screen -> Files.write(components.resolve("screen-${screen.slug}.png"), evidence.screenPng(screen)) }
        return components
    }

    private fun evidenceReceipt(
        overview: ByteArray,
        componentViewports: Map<DocumentedComponent, ShowcaseViewport>,
        componentImages: Map<DocumentedComponent, ByteArray>,
        screenImages: Map<String, ByteArray>,
    ): String =
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
                appendLine("component.${component.slug}.viewport.width=${viewport.size.width}")
                appendLine("component.${component.slug}.viewport.height=${viewport.size.height}")
                appendLine("component.${component.slug}.gui.scale=${viewport.scale}")
                appendLine("component.${component.slug}.fabric.headless.argb.sha256=${componentArgbHash(component)}")
                appendLine("component.${component.slug}.png.sha256=${sha256(bytes)}")
            }
            screenImages.forEach { (slug, bytes) -> appendLine("screen.$slug.png.sha256=${sha256(bytes)}") }
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
