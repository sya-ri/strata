package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * Verifies fresh compiled examples and final-density rasterization without Minecraft or a graphics context.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ShowcaseHeadlessRendererTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun compiledComponentsRenderDeterministicallyAtTheirDeclaredDensity() {
        val assets = ShowcaseMinecraftAssetFixture(temporary).assets()
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            val first = ShowcaseHeadlessRenderer.component(scenario, assets)
            val second = ShowcaseHeadlessRenderer.component(scenario, assets)
            assertArrayEquals(first, second, scenario.component.apiMethodName)
            assertEquals(scenario.viewportMetadata.physicalSize, pngSize(first))
        }
        assertArrayEquals(ShowcaseHeadlessRenderer.overview(assets), ShowcaseHeadlessRenderer.overview(assets))
        ShowcaseScenarioCatalog.screens.filter { it.screen != DocumentedScreen.SynchronizedInventory }.forEach { scenario ->
            assertArrayEquals(ShowcaseHeadlessRenderer.screen(scenario, assets), ShowcaseHeadlessRenderer.screen(scenario, assets))
        }
        val inventory = ShowcaseScenarioCatalog.screens.single { it.screen == DocumentedScreen.SynchronizedInventory }
        assertThrows(IllegalArgumentException::class.java) { ShowcaseHeadlessRenderer.screen(inventory, assets) }
    }

    @Test
    fun renderingNewUnicodeTextDoesNotRequireGameOrGraphicsClasses() {
        listOf("net.minecraft.client.Minecraft", "org.lwjgl.opengl.GL", "org.lwjgl.glfw.GLFW").forEach { name ->
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name, false, javaClass.classLoader) }
        }
        val assets = ShowcaseMinecraftAssetFixture(temporary).assets()
        val viewport = ShowcaseViewport(IntSize(96, 24), 2)
        val japanese = ShowcaseHeadlessRenderer.render(assets.profile, textScreen("日本語"), viewport)
        val korean = ShowcaseHeadlessRenderer.render(assets.profile, textScreen("한글 / 🙂"), viewport)
        assertEquals(viewport.physicalSize, pngSize(japanese))
        assertEquals(viewport.physicalSize, pngSize(korean))
        assertFalse(japanese.contentEquals(korean))
    }

    @Test
    fun higherDensitySamplesOriginalGlyphsInsteadOfEnlargingLogicalPixels() {
        val fixture = ShowcaseMinecraftAssetFixture(temporary)
        val inputs = inputs(fixture)
        val assets = ShowcaseMinecraftAssets(inputs, LwjglMinecraftFontBackendFactory)
        val definition = { textScreen("日本語") }
        val logicalSize = IntSize(96, 24)
        val lowPng = ShowcaseHeadlessRenderer.render(assets.profile, definition(), ShowcaseViewport(logicalSize, 1))
        val highPng = ShowcaseHeadlessRenderer.render(assets.profile, definition(), ShowcaseViewport(logicalSize, 2))
        LwjglMinecraftFontBackendFactory.open(inputs.compatibility).use { decoder ->
            val low = decoder.decodePng(lowPng)
            val high = decoder.decodePng(highPng)
            val enlarged =
                IntArray(high.size.width * high.size.height) { index ->
                    low.argbAt(index % high.size.width / 2, index / high.size.width / 2)
                }
            assertEquals(IntSize(logicalSize.width * 2, logicalSize.height * 2), high.size)
            assertFalse(high.copyArgb().contentEquals(enlarged), "Scale two must preserve source detail absent from the logical raster.")
        }
    }

    @Test
    fun overviewCompositesTransparentMenuOntoTheOpaqueFramebufferClear() {
        val fixture = ShowcaseMinecraftAssetFixture(temporary)
        val inputs = inputs(fixture)
        val assets = ShowcaseMinecraftAssets(inputs, LwjglMinecraftFontBackendFactory)
        assertEquals(0x40, assets.image(ShowcaseGuiAsset.MenuBackground.id).argbAt(0, 0) ushr 24)
        LwjglMinecraftFontBackendFactory.open(inputs.compatibility).use { decoder ->
            val overview = decoder.decodePng(ShowcaseHeadlessRenderer.overview(assets))
            assertEquals(0xFF, overview.argbAt(0, 0) ushr 24)
        }
    }

    private fun inputs(fixture: ShowcaseMinecraftAssetFixture): ShowcaseMinecraftInputs =
        ShowcaseMinecraftInputs(
            fixture.clientJar,
            fixture.assetIndex,
            fixture.assetObjects,
            fixture.versionManifest,
            fixture.testResources,
        )

    private fun textScreen(text: String): ScreenDefinition = ScreenDefinition("New source text") { Stack { Text(text) } }

    private fun pngSize(bytes: ByteArray): IntSize {
        val buffer = ByteBuffer.wrap(bytes)
        return IntSize(buffer.getInt(16), buffer.getInt(20))
    }
}
