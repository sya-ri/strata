package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Verifies deterministic generation evidence without loading Minecraft or a renderer.
 */
internal class ShowcaseHeadlessReceiptTest {
    @Test
    fun receiptOrdersLogicalInputsAndRecordsPhysicalDensityAndNativeException() {
        val frames = frames()
        val assets = linkedMapOf("version-manifest" to "2".repeat(64), "resource.client.assets/minecraft/font/default.json" to "1".repeat(64))
        val proofHash = "3".repeat(64)
        val first = ShowcaseHeadlessReceipt.create(assets, frames, proofHash)
        val reordered = ShowcaseHeadlessReceipt.create(assets.entries.reversed().associate { it.toPair() }, frames.entries.reversed().associate { it.toPair() }, proofHash)

        assertArrayEquals(first, reordered)
        val text = first.toString(Charsets.UTF_8)
        val lines = text.trimEnd('\n').lines()
        assertEquals(lines.sorted(), lines)
        assertTrue(text.endsWith('\n'))
        assertTrue(text.endsWith("\n\n").not())
        assertTrue(text.contains("generator=headless\n"))
        assertTrue(text.contains("component.text.viewport.width=192\n"))
        assertTrue(text.contains("component.text.gui.scale=2\n"))
        assertTrue(text.contains("component.text.physical.width=384\n"))
        assertTrue(text.contains("component.text.physical.height=176\n"))
        assertTrue(text.contains("component.text.origin=headless\n"))
        assertTrue(text.contains("screen.inventory.origin=loaded-server-fabric\n"))
        assertTrue(text.contains("screen.inventory.evidence.sha256=$proofHash\n"))
        assertTrue(text.contains("fabric.headless.argb.sha256").not())
    }

    @Test
    fun sourceAssetsAndImageChangesAlterOnlyTheCurrentReceipt() {
        val assets = mapOf("client-jar" to "1".repeat(64))
        val frames = frames().toMutableMap()
        val original = ShowcaseHeadlessReceipt.create(assets, frames, "3".repeat(64))
        val changedAssets = ShowcaseHeadlessReceipt.create(mapOf("client-jar" to "2".repeat(64)), frames, "3".repeat(64))
        assertTrue(original.contentEquals(changedAssets).not())
        val scenario = ShowcaseScenarioCatalog.components.single { it.component == DocumentedComponent.Text }
        val image = png(scenario.viewportMetadata)
        frames["component.text"] = ShowcaseFrameReceipt(scenario.viewportMetadata, "changed source", image)
        val changedSource = ShowcaseHeadlessReceipt.create(assets, frames, "3".repeat(64))
        assertTrue(original.contentEquals(changedSource).not())
        image[8] = 1
        frames["component.text"] = ShowcaseFrameReceipt(scenario.viewportMetadata, "changed source", image)
        assertTrue(changedSource.contentEquals(ShowcaseHeadlessReceipt.create(assets, frames, "3".repeat(64))).not())
    }

    @Test
    fun frameHashesNormalizeOnlyLineEndingsAndDoNotRetainCallerBuffers() {
        val viewport = ShowcaseViewport(IntSize(2, 3), 2)
        val image = png(viewport)
        val first = ShowcaseFrameReceipt(viewport, "a\r\nb\rc", image)
        val same = ShowcaseFrameReceipt(viewport, "a\nb\nc", image)
        image[8] = 1

        assertEquals(first.sourceSha256, same.sourceSha256)
        assertEquals(first.pngSha256, same.pngSha256)
        assertNotEquals(first.pngSha256, ShowcaseFrameReceipt(viewport, "a\nb\nc", image).pngSha256)
        assertNotEquals(first.sourceSha256, ShowcaseFrameReceipt(viewport, "a\nb\nc\n", image).sourceSha256)
        assertThrows(IllegalArgumentException::class.java) { ShowcaseFrameReceipt(ShowcaseViewport(IntSize(2, 3), 1), "source", image) }
    }

    @Test
    fun incompleteCoverageWrongProvenanceAndUnsafeAssetKeysAreRejected() {
        val frames = frames()
        val hash = "1".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseHeadlessReceipt.create(mapOf("client-jar" to hash), frames - "component.text", hash)
        }
        val viewport = ShowcaseViewport(IntSize(320, 240), 1)
        val wrongOrigin = frames + ("screen.inventory" to ShowcaseFrameReceipt(viewport, "source", png(viewport)))
        assertThrows(IllegalArgumentException::class.java) { ShowcaseHeadlessReceipt.create(mapOf("client-jar" to hash), wrongOrigin, hash) }
        listOf("C:/absolute/client.jar", "/absolute/client.jar", "source\nother", "source=other").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { ShowcaseHeadlessReceipt.create(mapOf(key to hash), frames, hash) }
        }
        assertThrows(IllegalArgumentException::class.java) { ShowcaseHeadlessReceipt.create(mapOf("client-jar" to "invalid"), frames, hash) }
    }

    private fun frames(): Map<String, ShowcaseFrameReceipt> =
        buildMap {
            val overview = ShowcaseScenarioCatalog.overview
            val overviewViewport = ShowcaseViewport(overview.viewport, overview.scale)
            put("overview", ShowcaseFrameReceipt(overviewViewport, "overview source", png(overviewViewport)))
            ShowcaseScenarioCatalog.components.forEach { scenario ->
                put("component.${scenario.component.slug}", ShowcaseFrameReceipt(scenario.viewportMetadata, "component source", png(scenario.viewportMetadata)))
            }
            ShowcaseScenarioCatalog.screens.forEach { scenario ->
                val viewport = ShowcaseViewport(IntSize(scenario.viewportWidth, scenario.viewportHeight), scenario.scale)
                val origin =
                    if (scenario.screen == DocumentedScreen.SynchronizedInventory) ShowcaseFrameReceipt.Origin.LoadedServerFabric else ShowcaseFrameReceipt.Origin.Headless
                put("screen.${scenario.screen.slug}", ShowcaseFrameReceipt(viewport, "screen source", png(viewport), origin))
            }
        }

    private fun png(viewport: ShowcaseViewport): ByteArray =
        ByteBuffer
            .allocate(24)
            .apply {
                put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                position(16)
                putInt(viewport.physicalSize.width)
                putInt(viewport.physicalSize.height)
            }.array()
}
