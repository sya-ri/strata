package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Builds a deterministic complete profile for common-runtime tests.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftProfileFixture {
    /**
     * Creates a profile whose glyph widths are stable and whose assets are immutable.
     *
     * @param menu immutable 16 by 16 menu image.
     * @param normalBorder normal sprite border.
     * @param normalCenterMode normal sprite center mode.
     * @param highlightedBorder highlighted sprite border.
     * @param highlightedCenterMode highlighted sprite center mode.
     * @param disabledBorder disabled sprite border.
     * @param disabledCenterMode disabled sprite center mode.
     * @return a complete profile.
     */
    fun create(
        menu: DrawImage = image(IntSize(16, 16), 0xFF101010.toInt()),
        normalBorder: Int = 3,
        normalCenterMode: MinecraftNineSliceCenterMode = MinecraftNineSliceCenterMode.Tiled,
        highlightedBorder: Int = 3,
        highlightedCenterMode: MinecraftNineSliceCenterMode = MinecraftNineSliceCenterMode.Tiled,
        disabledBorder: Int = 1,
        disabledCenterMode: MinecraftNineSliceCenterMode = MinecraftNineSliceCenterMode.Tiled,
    ): MinecraftUiProfile =
        createMinecraftUiProfile {
            menuBackground(menu)
            for (codePoint in 0x21..0x7E) {
                val x = (codePoint - 0x21) % 8
                val pixels = IntArray(64) { 0x00FFFFFF }
                pixels[x] = -1
                printableAsciiGlyph(codePoint, createDrawImage(IntSize(8, 8), pixels))
            }
            buttonNormal(image(IntSize(200, 20), 0xFF202020.toInt()), normalBorder, normalCenterMode)
            buttonHighlighted(image(IntSize(200, 20), 0xFF303030.toInt()), highlightedBorder, highlightedCenterMode)
            buttonDisabled(image(IntSize(200, 20), 0xFF404040.toInt()), disabledBorder, disabledCenterMode)
        }

    private fun image(
        size: IntSize,
        color: Int,
    ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
