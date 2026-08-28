@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftGlyphChannel
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies resource-font metrics, positioned glyphs, cache independence, and detached rendering without a loaded game.
 */
internal class MinecraftResourceTextTest {
    @Test
    fun nonFiniteAdvanceKeepsItsDrawablePrefixAndNativeWidthBeforeLayoutProjection() {
        val first = createDrawImage(IntSize(1, 1), intArrayOf(0xFFFF0000.toInt()))
        val second = createDrawImage(IntSize(1, 1), intArrayOf(0xFF0000FF.toInt()))
        for (saturating in listOf(false, true)) {
            val cases = listOf(Float.NaN to 0, Float.POSITIVE_INFINITY to if (saturating) Int.MAX_VALUE else Int.MIN_VALUE, Float.NEGATIVE_INFINITY to Int.MIN_VALUE)
            for ((advance, expectedWidth) in cases) {
                val glyphs =
                    mapOf(
                        'A'.code to MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, first),
                        'B'.code to MinecraftFontGlyph(advance, 0f, 0f, 1f, 1f, second),
                        'C'.code to MinecraftFontGlyph(3f, 0f, 0f, 1f, 1f, first),
                    )
                for (prepared in listOf(false, true)) {
                    val run = numericRun("ABC", glyphs, saturating, preparedTextBounds = prepared)
                    assertEquals(expectedWidth, run.nativeWidth)
                    assertEquals(IntSize(maxOf(0, expectedWidth), 9), run.size)
                    val commands = resourceTextCommands(run).filterIsInstance<DrawCommand.SampledImage>()
                    assertEquals(listOf(FloatRect(0f, 0f, 1f, 1f), FloatRect(2f, 0f, 3f, 1f)), commands.map { it.destination })
                    assertArrayEquals(
                        intArrayOf(0xFFFF0000.toInt(), 0, 0xFF0000FF.toInt(), 0, 0, 0),
                        rasterizeHeadless(commands, IntSize(6, 1)).copyArgb(),
                    )
                }
            }
        }
    }

    @Test
    fun invalidInkAndFinalTranslatedOverflowAreOmittedWithoutDiscardingFiniteGlyphs() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val visible = MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, image)
        val invalid =
            listOf(
                MinecraftFontGlyph(1f, Float.NaN, 0f, Float.POSITIVE_INFINITY, 1f, image),
                MinecraftFontGlyph(1f, -Float.MAX_VALUE, 0f, Float.MAX_VALUE, 1f, image),
            )
        for (glyph in invalid) {
            for (prepared in listOf(false, true)) {
                val run = numericRun("AXB", mapOf('A'.code to visible, 'X'.code to glyph, 'B'.code to visible), preparedTextBounds = prepared)
                assertEquals(5, run.nativeWidth)
                val commands = resourceTextCommands(run).filterIsInstance<DrawCommand.SampledImage>()
                assertEquals(listOf(FloatRect(0f, 0f, 1f, 1f), FloatRect(3f, 0f, 4f, 1f)), commands.map { it.destination })
            }
        }
        val extreme = MinecraftFontGlyph(1f, Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE, 1f, image, shadowOffset = Float.MAX_VALUE)
        val finiteForeground = numericRun("A", mapOf('A'.code to extreme), shadow = ArgbColor(-1), preparedTextBounds = true)
        val command = resourceTextCommands(finiteForeground).filterIsInstance<DrawCommand.SampledImage>().single()
        assertEquals(FloatRect(Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE, 1f), command.destination)
        val spacing = MinecraftFontGlyph(Float.MAX_VALUE, 0f, 0f, 0f, 0f, null)
        val translatedOverflow = numericRun("SA", mapOf('S'.code to spacing, 'A'.code to extreme))
        assertTrue(resourceTextCommands(translatedOverflow).isEmpty())
    }

    @Test
    fun preparedBoundsUseRawReversedAxesAndPackedShadowColorWithoutChangingWidth() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val normal = MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, image)
        for (orientation in listOf(SampledImageOrientation.FlipHorizontal, SampledImageOrientation.FlipVertical, SampledImageOrientation.FlipBoth)) {
            val reversed = normal.copy(orientation = orientation)
            val glyphs = mapOf('R'.code to reversed)
            val legacy = numericRun("R", glyphs)
            val prepared = numericRun("R", glyphs, preparedTextBounds = true)
            assertEquals(legacy.nativeWidth, prepared.nativeWidth)
            assertEquals(legacy.size, prepared.size)
            assertEquals(1, resourceTextCommands(legacy).size)
            assertTrue(resourceTextCommands(prepared).isEmpty())
            assertTrue(legacy.equivalentTo(prepared).not())
            assertTrue(resourceTextCommands(numericRun("R", glyphs, shadow = ArgbColor(-1), preparedTextBounds = true)).isEmpty())
            for (shadow in listOf(null, ArgbColor(0), ArgbColor(1), ArgbColor(-1))) {
                val expanded = numericRun("R", mapOf('R'.code to reversed.copy(shadowOffset = 2f)), shadow = shadow, preparedTextBounds = true)
                val expected = if (shadow != null && shadow.value != 0) 2 else 0
                assertEquals(expected, resourceTextCommands(expanded).size)
            }
        }
        val mixed = numericRun("AR", mapOf('A'.code to normal, 'R'.code to normal.copy(orientation = SampledImageOrientation.FlipBoth)), preparedTextBounds = true)
        val commands = resourceTextCommands(mixed).filterIsInstance<DrawCommand.SampledImage>()
        assertEquals(listOf(SampledImageOrientation.Normal, SampledImageOrientation.FlipBoth), commands.map { it.orientation })
        assertArrayEquals(intArrayOf(-1, 0, -1), rasterizeHeadless(commands, IntSize(3, 1)).copyArgb())
    }

    @Test
    fun preparedBoundsIncludeZeroAreaInkButNeverSpacingOnlyGlyphs() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val reversed = MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, image, orientation = SampledImageOrientation.FlipBoth)
        val point = MinecraftFontGlyph(0f, 0f, 2f, 0f, 2f, image)
        val spacing = point.copy(advance = Float.NaN, left = Float.NaN, top = Float.NaN, right = Float.NaN, bottom = Float.NaN, image = null)
        val glyphs = mapOf('R'.code to reversed, 'P'.code to point, 'S'.code to spacing)
        val withPoint = numericRun("RP", glyphs, preparedTextBounds = true)
        val commands = resourceTextCommands(withPoint).filterIsInstance<DrawCommand.SampledImage>()
        assertEquals(SampledImageOrientation.FlipBoth, commands.single().orientation)
        assertArrayEquals(intArrayOf(-1), rasterizeHeadless(commands, IntSize(1, 1)).copyArgb())
        val withSpacing = numericRun("RS", glyphs, preparedTextBounds = true)
        assertEquals(0, withSpacing.nativeWidth)
        assertTrue(resourceTextCommands(withSpacing).isEmpty())
    }

    @Test
    fun nestedFontSelectionsControlMixedUnicodeMetricsAndHeadlessPixels() {
        val fonts = List(3) { index -> ResourceId("test", "layer_$index") }
        val colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val sheets =
            colors.mapIndexed { index, color ->
                createDrawImage(IntSize(12, 8), IntArray(96) { pixel -> if (pixel % 4 <= index) color else 0 })
            }
        val files =
            fonts.flatMapIndexed { index, font ->
                listOf(
                    FontTestResources.font(
                        "${font.namespace}:${font.path}",
                        """{"type":"bitmap","file":"test:layer_$index.png","ascent":7,"chars":["A🙂B"]}""",
                    ),
                    "assets/test/textures/layer_$index.png" to byteArrayOf(index.toByte()),
                )
            }
        val snapshot = FontTestResources.snapshot(*files.toTypedArray())
        val decoder = FontTestBackend(decode = { bytes -> sheets[bytes.single().toInt()] })
        val backend =
            object : MinecraftFontBackend by decoder {
                override fun visualGlyphs(
                    text: String,
                    rightToLeft: Boolean,
                ): List<MinecraftVisualGlyph> {
                    assertEquals("AA🙂BB", text)
                    return decoder.visualGlyphs(text, rightToLeft)
                }
            }
        val text =
            UiText.concat(
                UiText.Literal("A"),
                UiText
                    .concat(
                        UiText.Literal("A"),
                        UiText.Literal("🙂").withFont(fonts[2]).withFont(fonts[0]),
                        UiText.Literal("B"),
                    ).withFont(fonts[1]),
                UiText.Literal("B"),
            )
        val run =
            MinecraftFontEngine(snapshot, { backend }).use { engine ->
                MinecraftTextRun.createFonts(text, engine, fonts[0], ArgbColor(-1), null)
            }
        assertEquals(IntSize(14, 9), run.size)
        val row = intArrayOf(colors[0], 0, colors[1], colors[1], 0, colors[2], colors[2], colors[2], 0, colors[1], colors[1], 0, colors[0], 0)
        val expected = IntArray(14 * 9) { index -> if (index / 14 < 8) row[index % 14] else 0 }
        assertArrayEquals(expected, rasterizeHeadless(resourceTextCommands(run), run.size).copyArgb())
    }

    @Test
    fun resourceTextMeasuresLogicalAdvancesAndPaintsShapedGlyphPositions() {
        val sheet =
            createDrawImage(
                IntSize(8, 8),
                IntArray(64) { index -> if (index == 3 || index == 4) -1 else 0 },
            )
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "test:shaped",
                    """
                    {"type":"space","advances":{"\u0644":3,"\u0627":4}},
                    {"type":"bitmap","file":"test:font/shaped.png","height":8,"ascent":7,"chars":["\uFEFBA"]}
                    """.trimIndent(),
                ),
                "assets/test/textures/font/shaped.png" to byteArrayOf(1),
            )
        val backend = FontTestBackend(decode = { sheet })
        val shapingBackend =
            object : MinecraftFontBackend by backend {
                override fun visualGlyphs(
                    text: String,
                    rightToLeft: Boolean,
                ): List<MinecraftVisualGlyph> {
                    assertEquals("\u0644\u0627A", text)
                    return listOf(MinecraftVisualGlyph(0xFEFB, 0), MinecraftVisualGlyph('A'.code, 1))
                }
            }
        val run =
            MinecraftFontEngine(snapshot, { shapingBackend }).use { engine ->
                assertThrows(IllegalArgumentException::class.java) {
                    MinecraftTextRun.createFonts(
                        UiText.Literal("\u00A7a"),
                        engine,
                        ResourceId("test", "shaped"),
                        ArgbColor(-1),
                        null,
                    )
                }
                MinecraftTextRun.createFonts(
                    UiText.Literal("\u0644\u0627A"),
                    engine,
                    ResourceId("test", "shaped"),
                    ArgbColor(-1),
                    null,
                )
            }
        assertEquals(1, backend.closeCalls)
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            assertEquals(IntSize(9, 9), tree.measure(Constraints(maxWidth = 20, maxHeight = 9)))
            tree.layout()
            val commands = tree.paint().filterIsInstance<DrawCommand.SampledImage>()
            assertEquals(
                listOf(FloatRect(0f, 0f, 4f, 8f), FloatRect(5f, 0f, 9f, 8f)),
                commands.map { command -> command.destination },
            )
            val rendered = rasterizeHeadless(commands, IntSize(9, 9))
            assertEquals(-1, rendered.argbAt(3, 0))
            assertEquals(0, rendered.argbAt(4, 0))
            assertEquals(-1, rendered.argbAt(5, 0))
        } finally {
            tree.close()
        }
    }

    @Test
    fun mixedProviderTextKeepsMetricsCommandsAndPixelsAcrossCacheChurnAndEngineClose() {
        val font = ResourceId("test", "cached")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "test:cached",
                    """
                    {"type":"bitmap","file":"test:cached.png","height":7,"ascent":6,"chars":["日한"]},
                    {"type":"ttf","file":"test:cached.ttf"}
                    """.trimIndent(),
                ),
                "assets/test/textures/cached.png" to byteArrayOf(1),
                "assets/test/font/cached.ttf" to byteArrayOf(2),
            )
        val sheet =
            createDrawImage(
                IntSize(6, 3),
                IntArray(18) { index -> if (index % 3 == 0) 0 else 0x80CC4422.toInt() or index },
            )
        val cachedBackend = resourceTextCacheBackend(sheet)
        val uncachedBackend = resourceTextCacheBackend(sheet)
        val text = UiText.Literal("日🙂한日")
        val foreground = ArgbColor(-1)
        val shadow = ArgbColor(0xFF3F3F3F.toInt())
        val runs =
            MinecraftFontEngine(snapshot, { cachedBackend }, cacheEntries = 3, cacheBytes = 108).use { cached ->
                MinecraftFontEngine(snapshot, { uncachedBackend }, cacheEntries = 0, cacheBytes = 0).use { uncached ->
                    val initial = MinecraftTextRun.createFonts(text, cached, font, foreground, shadow)
                    repeat(32) { index -> cached.glyph(font, 0x4E00 + index) }
                    val rebuilt = MinecraftTextRun.createFonts(text, cached, font, foreground, shadow)
                    assertTrue(initial.equivalentTo(rebuilt))
                    assertTrue(1 < cachedBackend.decodeCalls)
                    assertTrue(cached.retainedRasterEntries <= 3)
                    assertTrue(cached.retainedRasterBytes <= 108)
                    cached.close()
                    assertEquals(0, cached.retainedRasterEntries)
                    assertEquals(0L, cached.retainedRasterBytes)
                    assertEquals(0, cached.retainedFaces)
                    assertEquals(0, uncachedBackend.closeCalls)
                    val expected = MinecraftTextRun.createFonts(text, uncached, font, foreground, shadow)
                    assertTrue(initial.equivalentTo(expected))
                    assertEquals(0, uncached.retainedRasterEntries)
                    assertEquals(0L, uncached.retainedRasterBytes)
                    listOf(initial, rebuilt, expected)
                }
            }
        assertEquals(1, cachedBackend.closeCalls)
        assertEquals(1, uncachedBackend.closeCalls)
        assertEquals(List(3) { IntSize(29, 9) }, runs.map(MinecraftTextRun::size))
        val commands = runs.map(::resourceTextCommands)
        assertEquals(8, commands.first().size)
        for (actual in commands.drop(1)) {
            assertEquals(commands.first(), actual)
            for (scale in 1..3) {
                val expectedPixels = rasterizeHeadless(commands.first(), IntSize(33, 12), scale).copyArgb()
                assertArrayEquals(expectedPixels, rasterizeHeadless(actual, IntSize(33, 12), scale).copyArgb())
            }
        }
    }

    @Test
    fun zeroHeightResourceGlyphsAdvanceWithoutDrawingOrDiscardingSiblingProviders() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "test:zero",
                    """
                    {"type":"bitmap","file":"test:font/zero.png","height":0,"ascent":0,"chars":["A"]},
                    {"type":"space","advances":{"B":4}},
                    {"type":"bitmap","file":"test:font/visible.png","height":8,"ascent":7,"chars":["C"]}
                    """.trimIndent(),
                ),
                "assets/test/textures/font/zero.png" to byteArrayOf(1),
                "assets/test/textures/font/visible.png" to byteArrayOf(1),
            )
        val sheet = createDrawImage(IntSize(8, 8), IntArray(64) { -1 })
        val run =
            MinecraftFontEngine(snapshot, { FontTestBackend(decode = { sheet }) }).use { engine ->
                MinecraftTextRun.createFonts(UiText.Literal("ABC"), engine, ResourceId("test", "zero"), ArgbColor(-1), ArgbColor(0xFF3F3F3F.toInt()))
            }
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            assertEquals(IntSize(14, 9), tree.measure(Constraints(maxWidth = 20, maxHeight = 9)))
            tree.layout()
            val commands = tree.paint()
            assertEquals(2, commands.size)
            assertEquals(
                listOf(FloatRect(6f, 1f, 14f, 9f), FloatRect(5f, 0f, 13f, 8f)),
                commands.filterIsInstance<DrawCommand.SampledImage>().map { command -> command.destination },
            )
            val rendered = rasterizeHeadless(commands, IntSize(14, 9))
            assertEquals(0, rendered.argbAt(0, 7))
            assertEquals(-1, rendered.argbAt(5, 0))
        } finally {
            tree.close()
        }
    }

    @Test
    fun negativeHeightBitmapTextPreservesSignedCursorAndReversedSamplingAfterEngineClose() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font(
                    "test:negative",
                    """
                    {"type":"space","advances":{"P":8}},
                    {"type":"bitmap","file":"test:rows.png","height":-7,"ascent":-7,"chars":["A"]},
                    {"type":"bitmap","file":"test:rows.png","height":1,"ascent":1,"chars":["C"]}
                    """.trimIndent(),
                ),
                "assets/test/textures/rows.png" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(rasterizer = MinecraftTrueTypeRasterizer.Stb),
            )
        val sheet = createDrawImage(IntSize(8, 8), IntArray(64) { 0xFF000000.toInt() or (it / 8 + 1) })
        val font = ResourceId("test", "negative")
        val run =
            MinecraftFontEngine(snapshot, { FontTestBackend(decode = { sheet }) }).use { engine ->
                val negativeOnly = MinecraftTextRun.createFonts(UiText.Literal("A"), engine, font, ArgbColor(-1), null)
                assertEquals(IntSize(0, 9), negativeOnly.size)
                assertEquals(-5, negativeOnly.nativeWidth)
                MinecraftTextRun.createFonts(UiText.Literal("PAC"), engine, font, ArgbColor(-1), null)
            }
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            assertEquals(IntSize(5, 9), tree.measure(Constraints(maxWidth = 20, maxHeight = 9)))
            tree.layout()
            val commands = tree.paint().filterIsInstance<DrawCommand.SampledImage>()
            assertEquals(listOf(SampledImageOrientation.FlipBoth, SampledImageOrientation.Normal), commands.map { it.orientation })
            assertEquals(listOf(FloatRect(1.0000005f, 7f, 8f, 14f), FloatRect(3f, 6f, 4f, 7f)), commands.map { it.destination })
            for (scale in 1..3) {
                val rendered = rasterizeHeadless(commands, IntSize(9, 15), scale)
                assertEquals(0xFF000008.toInt(), rendered.argbAt(4 * scale, 7 * scale))
                assertEquals(0xFF000005.toInt(), rendered.argbAt(4 * scale, 10 * scale))
                assertEquals(0xFF000001.toInt(), rendered.argbAt(4 * scale, 13 * scale))
            }
        } finally {
            tree.close()
        }
    }

    private fun numericRun(
        text: String,
        glyphs: Map<Int, MinecraftFontGlyph>,
        saturatingCeil: Boolean = false,
        shadow: ArgbColor? = null,
        preparedTextBounds: Boolean = false,
    ): MinecraftTextRun {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:numeric.ttf"}"""),
                "assets/test/font/numeric.ttf" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturatingCeil, preparedTextBounds = preparedTextBounds),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph? = glyphs[codePoint]

                    override fun close() = Unit
                }
            })
        return MinecraftFontEngine(snapshot, { backend }).use { engine ->
            MinecraftTextRun.createFonts(UiText.Literal(text), engine, FontTestResources.defaultFont, ArgbColor(-1), shadow)
        }
    }

    private fun resourceTextCacheBackend(sheet: DrawImage): FontTestBackend =
        FontTestBackend(
            decode = { sheet },
            open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph =
                        MinecraftFontGlyph(
                            4.25f,
                            -0.75f,
                            0.5f,
                            1.25f,
                            3.5f,
                            createDrawImage(IntSize(2, 3), IntArray(6) { index -> (32 + (codePoint + index) % 192) * 0x01010101 }),
                            MinecraftGlyphChannel.Intensity,
                        )

                    override fun close() = Unit
                }
            },
        )

    private fun resourceTextCommands(run: MinecraftTextRun): List<DrawCommand> =
        UiTree().use { tree ->
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            tree.measure(Constraints())
            tree.layout()
            tree.paint()
        }
}
