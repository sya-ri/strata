@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextShadow
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Verifies independent text colors against literal pixel and command oracles in both font paths.
 */
internal class MinecraftTextStyleTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun inactiveForegroundCanOmitEveryShadowPixelAndCommand(resourceFonts: Boolean) {
        renderer(resourceFonts).use { renderer ->
            val original = renderer.create(UiText.Literal("A"), TextStyle.Inactive)
            val run = renderer.create(UiText.Literal("A"), TextStyle.Inactive.copy(shadow = null))
            val scope = MinecraftTextRecordingScope()
            run.paint(scope, 0, 0)
            assertEquals(original.size, run.size)
            assertEquals(original.nativeWidth, run.nativeWidth)
            assertEquals(original.text, run.text)
            assertEquals(1, scope.commands.size)
            for (scale in 1..3) {
                val pixels = rasterizeHeadless(scope.commands, IntSize(12, 12), scale)
                for (y in 0 until pixels.size.height) {
                    for (x in 0 until pixels.size.width) {
                        assertEquals(if (x < scale && y < scale) 0xffa0a0a0.toInt() else 0, pixels.argbAt(x, y))
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun automaticAndFixedShadowsPreserveAlphaAndUseTheEffectiveDisabledColor(resourceFonts: Boolean) {
        val foreground = ArgbColor(0x804080c0.toInt())
        val disabled = ArgbColor(0xc0804020.toInt())
        val fixedShadow = ArgbColor(0xa0206040.toInt())
        renderer(resourceFonts).use { renderer ->
            for (enabled in listOf(false, true)) {
                val automatic = ArgbColor(if (enabled) 0x80102030.toInt() else 0xc0201008.toInt())
                for (shadow in listOf(null, TextShadow.Automatic, TextShadow.Color(fixedShadow))) {
                    val run = renderer.create(UiText.Literal("A"), TextStyle(foreground, shadow, disabled), enabled)
                    val scope = MinecraftTextRecordingScope()
                    run.paint(scope, 0, 0)
                    val commands = scope.commands.filterIsInstance<DrawCommand.SampledImage>()
                    assertEquals(if (shadow == null) 1 else 2, commands.size)
                    assertEquals(if (enabled) foreground else disabled, commands.last().tint)
                    val expected = if (shadow is TextShadow.Color) fixedShadow else automatic
                    if (shadow != null) {
                        assertEquals(expected, commands.first().tint)
                    }
                }
            }
            val run = renderer.create(UiText.Literal("A"), TextStyle(foreground, shadow = null), enabled = false)
            val scope = MinecraftTextRecordingScope()
            run.paint(scope, 0, 0)
            assertEquals(foreground, (scope.commands.single() as DrawCommand.SampledImage).tint)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun presetsRetainTheirExactForegroundAndShadowPixelsInBothEnabledStates(resourceFonts: Boolean) {
        val cases =
            listOf(
                Triple(TextStyle.Normal, 0xffffffff.toInt(), 0xff3f3f3f.toInt()),
                Triple(TextStyle.Inactive, 0xffa0a0a0.toInt(), 0xff282828.toInt()),
                Triple(TextStyle.ContainerLabel, 0xff404040.toInt(), 0),
            )
        renderer(resourceFonts).use { renderer ->
            for (enabled in listOf(false, true)) {
                for ((style, foreground, shadow) in cases + Triple(TextStyle.TextField, if (enabled) 0xffe0e0e0.toInt() else 0xff707070.toInt(), if (enabled) 0xff383838.toInt() else 0xff1c1c1c.toInt())) {
                    val scope = MinecraftTextRecordingScope()
                    renderer.create(UiText.Literal("A"), style, enabled).paint(scope, 0, 0)
                    val image = rasterizeHeadless(scope.commands, IntSize(12, 12))
                    assertEquals(foreground, image.argbAt(0, 0))
                    assertEquals(shadow, image.argbAt(1, 1))
                    if (resourceFonts.not()) assertTrue(scope.commands.all { it is DrawCommand.BlitImage })
                }
            }
        }
    }

    /**
     * Creates independent fonts whose only nontransparent texel is the top-left pixel.
     */
    private fun renderer(resourceFonts: Boolean): MinecraftTextRenderer {
        if (resourceFonts) {
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("minecraft:default", """{"type":"bitmap","file":"test:glyph.png","ascent":7,"chars":["A"]}"""),
                    "assets/test/textures/glyph.png" to byteArrayOf(1),
                )
            return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { FontTestBackend(decode = { mask(-1) }) }))
        }
        val glyph = MinecraftGlyphSnapshot.create(2, mask(0xff3f3f3f.toInt()), mask(-1), mask(0xff282828.toInt()), mask(0xffa0a0a0.toInt()), mask(0xff383838.toInt()), mask(0xffe0e0e0.toInt()), mask(0xff1c1c1c.toInt()), mask(0xff707070.toInt()), mask(0xff404040.toInt()))
        return MinecraftTextRenderer.legacy(mapOf('A'.code to glyph))
    }

    /**
     * Supplies a white-compatible glyph mask independently of the implementation under test.
     */
    private fun mask(color: Int): DrawImage = createDrawImage(IntSize(8, 8), IntArray(64) { if (it == 0) color else 0 })
}
