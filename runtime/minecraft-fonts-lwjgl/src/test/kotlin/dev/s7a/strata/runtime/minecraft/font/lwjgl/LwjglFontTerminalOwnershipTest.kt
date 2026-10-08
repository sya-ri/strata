package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises real CPU native owners when a delegated backend closes its engine before returning a face or decoded image.
 * Every configured dependency-generation worker runs these tests without game or graphics state.
 */
internal class LwjglFontTerminalOwnershipTest {
    @Test
    fun returnedNativeFaceCannotRepopulateTheClosedEngineOrInvalidateDetachedPixels() {
        for (rasterizer in rasterizers()) {
            for (entries in listOf(0, 2, 4_096)) verifyNativeFace(rasterizer, entries)
        }
    }

    private fun verifyNativeFace(
        rasterizer: MinecraftTrueTypeRasterizer,
        entries: Int,
    ) {
        val selected = compatibility(rasterizer)
        val snapshot = snapshot(selected, """{"type":"ttf","file":"test:face.ttf"}""", "assets/test/font/face.ttf" to fixture())
        lateinit var owner: MinecraftFontEngine
        var opens = 0
        var glyphs = 0
        var closes = 0
        var backendCloses = 0
        var retained: MinecraftFontGlyph? = null
        var returned: MinecraftTrueTypeFace? = null
        LwjglMinecraftFontBackend(rasterizer).use { native ->
            val delegated =
                object : MinecraftBoundedFontBackend by native {
                    override fun openTrueType(
                        bytes: ByteArray,
                        settings: MinecraftTrueTypeSettings,
                        limits: MinecraftFontLoadLimits,
                    ): MinecraftTrueTypeFace {
                        opens++
                        val face = native.openTrueType(bytes, settings, limits)
                        returned = face
                        retained = face.glyph('日'.code)
                        owner.close()
                        return object : MinecraftTrueTypeFace {
                            override fun glyph(codePoint: Int): MinecraftFontGlyph? {
                                glyphs++
                                return face.glyph(codePoint)
                            }

                            override fun close() {
                                closes++
                                owner.close()
                                face.close()
                            }
                        }
                    }

                    override fun close() {
                        backendCloses++
                        owner.close()
                        native.close()
                    }
                }
            owner = MinecraftFontEngine(snapshot, { delegated }, cacheEntries = entries)
            val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
            assertEquals(missing, owner.glyph(ResourceId("minecraft", "default"), 'A'.code))
            assertTerminal(owner)
            assertEquals(1, opens)
            assertEquals(0, glyphs)
            assertEquals(1, closes)
            assertEquals(1, backendCloses)
            assertNotNull(checkNotNull(retained).image)
            assertThrows(IllegalStateException::class.java) { checkNotNull(returned).glyph('日'.code) }
            assertEquals(0, (field(native, "faces") as Set<*>).size)
            assertNull(field(checkNotNull(returned), "delegate"))
            MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory).use { independent ->
                assertEquals(retained, independent.glyph(ResourceId("minecraft", "default"), '日'.code))
            }
        }
    }

    @Test
    fun realPngReturnedAfterCloseRetainsPixelsWithoutCachingResourceKeys() {
        for (rasterizer in rasterizers()) {
            val selected = compatibility(rasterizer)
            val expected = rasterizeHeadless(listOf(DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(0x8055aaff.toInt()))), IntSize(8, 8))
            val snapshot = snapshot(selected, """{"type":"bitmap","file":"test:face.png","ascent":7,"chars":["A"]}""", "assets/test/textures/face.png" to expected.encodePng())
            for (entries in listOf(0, 2, 4_096)) {
                lateinit var owner: MinecraftFontEngine
                var decodes = 0
                var closes = 0
                var retained: DrawImage? = null
                LwjglMinecraftFontBackend(rasterizer).use { native ->
                    val delegated =
                        object : MinecraftBoundedFontBackend by native {
                            override fun decodePng(
                                bytes: ByteArray,
                                limits: MinecraftFontLoadLimits,
                            ): DrawImage {
                                decodes++
                                val image = native.decodePng(bytes, limits)
                                retained = image
                                owner.close()
                                return image
                            }

                            override fun close() {
                                closes++
                                owner.close()
                                native.close()
                            }
                        }
                    owner = MinecraftFontEngine(snapshot, { delegated }, cacheEntries = entries)
                    val missing = owner.glyph(ResourceId("unknown", "terminal"), 'A'.code)
                    assertEquals(missing, owner.glyph(ResourceId("minecraft", "default"), 'A'.code))
                    assertTerminal(owner)
                    assertEquals(1, decodes)
                    assertEquals(1, closes)
                    val image = checkNotNull(retained)
                    assertEquals(expected.size, image.size)
                    for (y in 0 until 8) {
                        for (x in 0 until 8) assertEquals(expected.argbAt(x, y), image.argbAt(x, y))
                    }
                    assertEquals(0, (field(native, "faces") as Set<*>).size)
                }
            }
        }
    }

    private fun assertTerminal(engine: MinecraftFontEngine) {
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0L, field(engine, "faceBytes"))
        for (name in listOf("rasters", "faces", "bitmapSizes", "bitmapFailures", "faceFailures", "providerStatus", "fontStatus")) {
            assertEquals(0, (field(engine, name) as Map<*, *>).size, name)
        }
        assertEquals(0, (field(engine, "validatedFaces") as Set<*>).size)
        assertNull(field(engine, "snapshot"))
        assertNull(field(engine, "backend"))
        engine.close()
        val rejected = assertThrows(IllegalStateException::class.java) { engine.glyph(ResourceId("minecraft", "default"), 'A'.code) }
        assertEquals("Font engine is closed.", rejected.message)
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun snapshot(
        selected: MinecraftFontCompatibility,
        provider: String,
        asset: Pair<String, ByteArray>,
    ): MinecraftFontSnapshot =
        MinecraftFontSnapshot.load(
            listOf(MinecraftMemoryFontAssetSource("terminal-font-test", mapOf("assets/minecraft/font/default.json" to """{"providers":[$provider]}""".toByteArray(), asset))),
            selected,
        )

    private fun compatibility(rasterizer: MinecraftTrueTypeRasterizer): MinecraftFontCompatibility =
        MinecraftFontCompatibility(
            rasterizer,
            32,
            bakedGlyphMetrics = System.getProperty("strata.fontBakedGlyphMetrics", "false").toBooleanStrict(),
            saturatingCeil = System.getProperty("strata.fontSaturatingCeil", "false").toBooleanStrict(),
        )

    private fun rasterizers(): List<MinecraftTrueTypeRasterizer> = System.getProperty("strata.fontRasterizer")?.let { listOf(MinecraftTrueTypeRasterizer.valueOf(it)) } ?: MinecraftTrueTypeRasterizer.entries

    private fun fixture(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fonts/strata-test.ttf")).use { it.readBytes() }
}
