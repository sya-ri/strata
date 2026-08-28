package dev.s7a.strata.runtime.minecraft.font.lwjgl

import com.ibm.icu.util.VersionInfo
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.lwjgl.Version
import kotlin.math.abs

/**
 * Verifies CPU-only decoding, scalar rasterization, and independent native resource ownership.
 */
internal class LwjglMinecraftFontBackendTest {
    @Test
    fun `worker contains only one selected CPU dependency generation and no game or graphics adapter`() {
        listOf(
            "net/minecraft/client/Minecraft.class",
            "net/fabricmc/loader/api/FabricLoader.class",
            "org/lwjgl/glfw/GLFW.class",
            "org/lwjgl/opengl/GL.class",
        ).forEach { resource -> assertTrue(classSources(resource).isEmpty(), resource) }
        val lwjgl = System.getProperty("strata.fontLwjglVersion")
        assertClassArtifact("org/lwjgl/Version.class", "lwjgl", lwjgl, System.getProperty("strata.fontCoreClassifier").orEmpty())
        assertClassArtifact("org/lwjgl/stb/STBImage.class", "lwjgl-stb", lwjgl)
        if (MinecraftTrueTypeRasterizer.FreeType in rasterizers()) {
            assertClassArtifact("org/lwjgl/util/freetype/FreeType.class", "lwjgl-freetype", lwjgl)
        } else {
            assertTrue(classSources("org/lwjgl/util/freetype/FreeType.class").isEmpty())
        }
        assertClassArtifact("com/ibm/icu/text/Bidi.class", "icu4j", System.getProperty("strata.fontIcuVersion"))
        assertClassArtifact("com/google/gson/Gson.class", "gson", System.getProperty("strata.fontGsonVersion"))
        if (lwjgl != null) {
            assertEquals(
                lwjgl,
                Version
                    .getVersion()
                    .substringBefore('+')
                    .substringBefore('-')
                    .substringBefore(' '),
            )
        }
        System.getProperty("strata.fontJavaVersion")?.let { expected -> assertEquals(expected.toInt(), Runtime.version().feature()) }
        println("CPU font runtime: LWJGL ${Version.getVersion()}, ICU ${VersionInfo.ICU_VERSION}, Java ${Runtime.version()}, natives ${System.getProperty("strata.fontNativeClassifier")}")
    }

    @Test
    fun `decodes PNG without a game display or graphics context`() {
        val expected = rasterizeHeadless(listOf(DrawCommand.FillRectangle(IntRect(0, 0, 2, 3), ArgbColor(0x8055aaff.toInt()))), IntSize(2, 3))
        LwjglMinecraftFontBackendFactory.open(compatibility(MinecraftTrueTypeRasterizer.Stb)).use { backend ->
            val actual = backend.decodePng(expected.encodePng())
            assertEquals(expected.size, actual.size)
            assertEquals(expected.argbAt(0, 0), actual.argbAt(0, 0))
            assertThrows(IllegalArgumentException::class.java) { backend.decodePng(byteArrayOf(1, 2, 3)) }
        }
    }

    @Test
    fun `selected native providers render new supplementary and CJK glyphs from supplied bytes`() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings(11f, 2f, 0.25f, -0.5f)).use { face ->
                    listOf(0x41, 0x65e5, 0xd55c, 0x1f642).forEach { codePoint ->
                        val glyph = checkNotNull(face.glyph(codePoint))
                        assertTrue(0f < glyph.advance)
                        val image = checkNotNull(glyph.image)
                        assertTrue(image.copyArgb().any { pixel -> (pixel ushr 24) in 1..254 })
                        assertTrue(image.copyArgb().all { pixel -> (pixel and 0xff) == (pixel ushr 24) })
                    }
                    assertNull(face.glyph(0x2603))
                    assertNull(checkNotNull(face.glyph(0x20)).image)
                }
            }
        }
    }

    @Test
    fun `zero size preserves each native provider instead of forcing shared empty glyphs`() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                listOf(1f, 2f).forEach { oversample ->
                    val settings = MinecraftTrueTypeSettings(0f, oversample)
                    backend.openTrueType(fixture(), settings).use { zero ->
                        verifyZeroGlyphs(rasterizer, backend, zero, settings)
                        assertNull(zero.glyph(0x2603))
                    }
                }
            }
        }
    }

    private fun verifyZeroGlyphs(
        rasterizer: MinecraftTrueTypeRasterizer,
        backend: MinecraftFontBackend,
        zero: MinecraftTrueTypeFace,
        settings: MinecraftTrueTypeSettings,
    ) {
        val codePoints = listOf(0x41, 0x65e5, 0xd55c, 0x1f642)
        when (rasterizer) {
            MinecraftTrueTypeRasterizer.Stb -> {
                codePoints.forEach { codePoint ->
                    val glyph = checkNotNull(zero.glyph(codePoint))
                    assertEquals(0f, glyph.advance)
                    assertNull(glyph.image)
                }
            }

            MinecraftTrueTypeRasterizer.FreeType -> {
                backend.openTrueType(fixture(), settings.copy(size = 1f / settings.oversample)).use { minimum ->
                    codePoints.forEach { codePoint ->
                        val expected = checkNotNull(minimum.glyph(codePoint))
                        assertNotNull(expected.image)
                        assertEquals(expected, zero.glyph(codePoint))
                    }
                }
            }
        }
    }

    @Test
    fun `zero scale STB glyphs retain fractional shift bounds with transparent pixels`() {
        rasterizers().filter { it == MinecraftTrueTypeRasterizer.Stb }.forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings(0f, 1f, 0.25f, 0.75f)).use { face ->
                    val glyph = checkNotNull(face.glyph(0x41))
                    assertEquals(0f, glyph.advance)
                    assertEquals(0.25f, glyph.left)
                    assertEquals(1.25f, glyph.right)
                    assertEquals(-2.25f, glyph.top)
                    assertEquals(-1.25f, glyph.bottom)
                    val image = checkNotNull(glyph.image)
                    assertEquals(IntSize(1, 1), image.size)
                    assertEquals(0, image.argbAt(0, 0))
                }
            }
        }
    }

    @Test
    @Timeout(5)
    fun tinyNegativeStbScalePreservesMeasuredRaster() {
        println("Tiny negative STB probe: test entered")
        rasterizers().filter { it == MinecraftTrueTypeRasterizer.Stb }.forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                println("Tiny negative STB probe: backend opened")
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings(-0.01f, 1f, 0.25f, 0.75f)).use { face ->
                    println("Tiny negative STB probe: face opened")
                    println("Tiny negative STB probe: calling glyph U+0041")
                    val glyph = checkNotNull(face.glyph(0x41))
                    println("Tiny negative STB probe: glyph returned")
                    assertEquals(700f * (-0.01f / 1000f), glyph.advance)
                    val image = checkNotNull(glyph.image)
                    assertEquals(IntSize(1, 1), image.size)
                    assertNull(glyph.oversizedRasterSize)
                    println("Tiny negative STB raster: advanceBits=${glyph.advance.toRawBits()}, pixel=${image.argbAt(0, 0).toUInt().toString(16)}")
                }
            }
        }
    }

    @Test
    fun signedProviderSettingsKeepIdenticalPixelsWithReversedLogicalAxes() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                val negative = MinecraftTrueTypeSettings(-12.75f, -2.5f, 0.35f, -0.2f)
                val positive = MinecraftTrueTypeSettings(12.75f, 2.5f, -0.35f, 0.2f)
                backend.openTrueType(fixture(), negative).use { reversed ->
                    backend.openTrueType(fixture(), positive).use { normal ->
                        verifyReversedGlyphs(reversed, normal)
                    }
                }
            }
        }
    }

    private fun verifyReversedGlyphs(
        reversed: MinecraftTrueTypeFace,
        normal: MinecraftTrueTypeFace,
    ) {
        listOf(0x41, 0x65e5, 0xd55c, 0x1f642).forEach { codePoint ->
            val expected = checkNotNull(normal.glyph(codePoint))
            val actual = checkNotNull(reversed.glyph(codePoint))
            assertEquals(-expected.advance, actual.advance)
            assertEquals(expected.image, actual.image)
            assertEquals(-expected.right, actual.left)
            assertEquals(-expected.left, actual.right)
            assertTrue(actual.top <= actual.bottom)
            assertEquals(SampledImageOrientation.FlipBoth, actual.orientation)
        }
    }

    @Test
    fun signedZeroOversamplingRetainsNativeIeeeMetricsWithoutBecomingMissingCharacters() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                listOf(0f, -0f).forEach { oversample ->
                    backend.openTrueType(fixture(), MinecraftTrueTypeSettings(11f, oversample)).use { face ->
                        verifyZeroOversamplingGlyph(face, rasterizer, oversample)
                    }
                }
            }
        }
    }

    private fun verifyZeroOversamplingGlyph(
        face: MinecraftTrueTypeFace,
        rasterizer: MinecraftTrueTypeRasterizer,
        oversample: Float,
    ) {
        val glyph = checkNotNull(face.glyph(0x41))
        when (rasterizer) {
            MinecraftTrueTypeRasterizer.Stb -> {
                assertTrue(glyph.advance.isNaN())
                assertNull(glyph.image)
            }

            MinecraftTrueTypeRasterizer.FreeType -> {
                assertEquals(1f / oversample, glyph.advance)
                assertNotNull(glyph.image)
                assertTrue(listOf(glyph.left, glyph.top, glyph.right, glyph.bottom).any { value -> value.isFinite().not() })
            }
        }
        assertNull(glyph.oversizedRasterSize)
        assertNotNull(face.glyph(0x20))
        assertNull(face.glyph(0x2603))
    }

    @Test
    fun negativePixelProductsKeepEmptyStbGlyphsAndMeasuredFreeTypeAtlasFailures() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                listOf(MinecraftTrueTypeSettings(-11f, 1f), MinecraftTrueTypeSettings(11f, -2f)).forEach { settings ->
                    verifyNegativePixelProduct(backend, settings, rasterizer)
                }
            }
        }
    }

    private fun verifyNegativePixelProduct(
        backend: MinecraftFontBackend,
        settings: MinecraftTrueTypeSettings,
        rasterizer: MinecraftTrueTypeRasterizer,
    ) {
        backend.openTrueType(fixture(), settings).use { face ->
            val glyph = checkNotNull(face.glyph(0x41))
            assertNull(glyph.image)
            when (rasterizer) {
                MinecraftTrueTypeRasterizer.Stb -> {
                    assertEquals(700f * (settings.size * settings.oversample / 1000f) / settings.oversample, glyph.advance)
                    assertNull(glyph.oversizedRasterSize)
                }

                MinecraftTrueTypeRasterizer.FreeType -> {
                    verifyClampedFreeTypeMetrics(backend, settings, glyph)
                }
            }
            assertNull(face.glyph(0x2603))
        }
    }

    private fun verifyClampedFreeTypeMetrics(
        backend: MinecraftFontBackend,
        settings: MinecraftTrueTypeSettings,
        glyph: MinecraftFontGlyph,
    ) {
        val positive = MinecraftTrueTypeSettings(65535f / abs(settings.oversample), abs(settings.oversample))
        backend.openTrueType(fixture(), positive).use { face ->
            val expected = checkNotNull(face.glyph(0x41))
            val size = checkNotNull(glyph.oversizedRasterSize)
            assertTrue(256 < size.width || 256 < size.height)
            assertEquals(expected.oversizedRasterSize, size)
            assertEquals(if (settings.oversample < 0f) -expected.advance else expected.advance, glyph.advance)
        }
    }

    @Test
    fun fractionalNegativeFreeTypeSizeKeepsTheNativeMinimumPixelRequest() {
        rasterizers().filter { it == MinecraftTrueTypeRasterizer.FreeType }.forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings(-0.25f)).use { negative ->
                    backend.openTrueType(fixture(), MinecraftTrueTypeSettings(0f)).use { zero ->
                        assertEquals(zero.glyph(0x41), negative.glyph(0x41))
                        assertNotNull(checkNotNull(negative.glyph(0x41)).image)
                    }
                }
            }
        }
    }

    @Test
    fun nativeAtlasLimitsPrecedeRasterAllocationAndIncludeTheExactBoundary() {
        listOf(IntSize(257, 1), IntSize(1, 257), IntSize(Int.MAX_VALUE, Int.MAX_VALUE)).forEach { size ->
            val glyph = TrueTypeGlyphMetrics(3f, 0f, 0f, 1f, 1f, size).rasterize { error("Oversized pixels must not be allocated.") }
            assertEquals(size, glyph.oversizedRasterSize)
            assertEquals(3f, glyph.advance)
            assertNull(glyph.image)
        }
        val boundary = IntSize(256, 256)
        val image = createDrawImage(boundary, IntArray(256 * 256))
        val glyph = TrueTypeGlyphMetrics(3f, 0f, 0f, 256f, 256f, boundary).rasterize { image }
        assertEquals(image, glyph.image)
        assertNull(glyph.oversizedRasterSize)
    }

    @Test
    fun numericProvidersPreserveSiblingSelectionAndNativeAtlasFallbackMetrics() {
        rasterizers().forEach { rasterizer ->
            listOf(MinecraftTrueTypeSettings(11f, 0f), MinecraftTrueTypeSettings(-11f, -2f), MinecraftTrueTypeSettings(512f)).forEach { settings ->
                val selected = compatibility(rasterizer)
                val snapshot = numericSnapshot(selected, settings)
                LwjglMinecraftFontBackendFactory.open(selected).use { backend ->
                    backend.openTrueType(fixture(), settings).use { face ->
                        val raw = checkNotNull(face.glyph('日'.code))
                        verifyNumericProviderSelection(snapshot, selected, raw)
                    }
                }
            }
        }
    }

    private fun verifyNumericProviderSelection(
        snapshot: MinecraftFontSnapshot,
        selected: MinecraftFontCompatibility,
        raw: MinecraftFontGlyph,
    ) {
        MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory).use { engine ->
            val font = ResourceId("minecraft", "default")
            assertEquals(3f, engine.glyph(font, 'A'.code).advance)
            assertEquals(9f, engine.glyph(font, 0x2603).advance)
            val actual = engine.glyph(font, '日'.code)
            val expectedAdvance = if (raw.oversizedRasterSize != null && selected.bakedGlyphMetrics) 6f else raw.advance
            assertEquals(expectedAdvance, actual.advance)
            assertNull(actual.oversizedRasterSize)
            if (raw.oversizedRasterSize != null) assertEquals(IntSize(5, 8), checkNotNull(actual.image).size)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    private fun numericSnapshot(
        selected: MinecraftFontCompatibility,
        settings: MinecraftTrueTypeSettings,
    ): MinecraftFontSnapshot {
        val document =
            """
            {"providers":[
              {"type":"space","advances":{"A":3}},
              {"type":"ttf","file":"test:custom.ttf","size":${settings.size},"oversample":${settings.oversample}},
              {"type":"space","advances":{"日":17,"☃":9}}
            ]}
            """.trimIndent()
        val source = MinecraftMemoryFontAssetSource("numeric-font-test", mapOf("assets/minecraft/font/default.json" to document.toByteArray(), "assets/test/font/custom.ttf" to fixture()))
        return MinecraftFontSnapshot.load(listOf(source), selected)
    }

    @Test
    fun undefinedStbIntegerConversionsFailBeforeRasterizationWithoutLosingFaceOwnership() {
        rasterizers().filter { it == MinecraftTrueTypeRasterizer.Stb }.forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                listOf(
                    MinecraftTrueTypeSettings(Float.MAX_VALUE, 2f),
                    MinecraftTrueTypeSettings(11f, 1f, Float.MAX_VALUE, 0f),
                    MinecraftTrueTypeSettings(11f, 1f, 0f, -Float.MAX_VALUE),
                    MinecraftTrueTypeSettings(4_000_000_000f),
                ).forEach { settings ->
                    backend.openTrueType(fixture(), settings).use { face ->
                        assertThrows(IllegalArgumentException::class.java) { face.glyph(0x41) }
                        assertNotNull(face.glyph(0x20))
                        assertNull(face.glyph(0x2603))
                    }
                }
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings()).use { face -> assertNotNull(checkNotNull(face.glyph(0x41)).image) }
            }
        }
    }

    @Test
    fun `closing one owner preserves another owner and detached glyphs`() {
        val selected = rasterizers().first()
        val first = LwjglMinecraftFontBackendFactory.open(compatibility(selected))
        LwjglMinecraftFontBackendFactory.open(compatibility(selected)).use { second ->
            val face = first.openTrueType(fixture(), MinecraftTrueTypeSettings())
            val retained = checkNotNull(face.glyph(0x41))
            first.close()
            first.close()
            assertThrows(IllegalStateException::class.java) { face.glyph(0x41) }
            assertNotNull(retained.image)
            second.openTrueType(fixture(), MinecraftTrueTypeSettings()).use { assertEquals(retained, it.glyph(0x41)) }
        }
    }

    @Test
    fun `native initialization failure leaves the backend usable`() {
        rasterizers().forEach { rasterizer ->
            LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                assertThrows(RuntimeException::class.java) { backend.openTrueType(byteArrayOf(0), MinecraftTrueTypeSettings()) }
                backend.openTrueType(fixture(), MinecraftTrueTypeSettings()).use { assertNotNull(it.glyph(0x1f642)) }
            }
        }
    }

    @Test
    fun `shaping and visual order keep supplementary source offsets on scalar boundaries`() {
        LwjglMinecraftFontBackendFactory.open(compatibility(rasterizers().first())).use { backend ->
            assertEquals(
                listOf(MinecraftVisualGlyph('日'.code, 0), MinecraftVisualGlyph('한'.code, 1), MinecraftVisualGlyph(0x1f642, 2)),
                backend.visualGlyphs("日한🙂", false),
            )
            assertEquals(listOf(MinecraftVisualGlyph(0xfe8f, 0)), backend.visualGlyphs("ب", true))
            assertEquals(
                listOf(MinecraftVisualGlyph('ב'.code, 3), MinecraftVisualGlyph(0x1f642, 1), MinecraftVisualGlyph('א'.code, 0)),
                backend.visualGlyphs("א🙂ב", true),
            )
            assertEquals("ב🙂א", backend.visualOrder("א🙂ב", true))
        }
    }

    private fun classSources(resource: String): List<String> =
        javaClass.classLoader
            .getResources(resource)
            .toList()
            .map { source -> source.toExternalForm() }

    private fun assertClassArtifact(
        resource: String,
        artifact: String,
        version: String?,
        classifier: String = "",
    ) {
        val sources = classSources(resource)
        assertEquals(1, sources.size, "Expected one $resource provider: $sources")
        if (version != null) {
            val suffix = if (classifier.isEmpty()) "" else "-$classifier"
            assertTrue(sources.single().endsWith("/$artifact-$version$suffix.jar!/$resource"), sources.single())
        }
    }

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
