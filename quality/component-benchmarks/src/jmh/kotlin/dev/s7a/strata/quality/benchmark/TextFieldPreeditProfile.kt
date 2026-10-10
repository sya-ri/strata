package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Complete validated ASCII profile with one opaque texel per glyph and exact two-pixel advances.
 * Resource controls replace only fonts with explicit detached scalar/space providers.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object TextFieldPreeditProfile {
    /**
     * Builds immutable inputs once, outside both complete-operation timing boundaries.
     */
    fun create(snapshot: MinecraftFontSnapshot?): MinecraftUiProfile =
        createMinecraftUiProfile {
            menuBackground(image(16, 16))
            containerBackground(image(256, 256))
            slotHighlightBack(image(24, 24))
            slotHighlightFront(image(24, 24))
            listBackground(image(16, 16))
            listHeaderSeparator(image(32, 2))
            listFooterSeparator(image(32, 2))
            scrollbarBackground(image(6, 32))
            scrollbarThumb(image(6, 32))
            checkbox(image(20, 20))
            checkboxHighlighted(image(20, 20))
            checkboxSelected(image(20, 20))
            checkboxSelectedHighlighted(image(20, 20))
            slider(image(200, 20), 1, NineSliceCenterMode.Tiled)
            sliderHighlighted(image(200, 20), 1, NineSliceCenterMode.Tiled)
            sliderHandle(image(8, 20))
            sliderHandleHighlighted(image(8, 20))
            loadingIndicator(image(5, 6))
            progressBarBorder(image(12, 12))
            progressBarFill(image(6, 6))
            progressBarFull(image(6, 6))
            tooltipBackground(image(100, 100))
            tooltipFrame(image(100, 100))
            textFieldNormal(image(200, 20))
            textFieldHighlighted(image(200, 20))
            if (snapshot == null) {
                for (scalar in 0x21..0x7E) {
                    printableAsciiGlyph(scalar, createDrawImage(IntSize(8, 8), IntArray(64) { if (it == 0) -1 else 0x00FFFFFF }))
                }
            } else {
                fonts(snapshot)
            }
            buttonNormal(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonHighlighted(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonDisabled(image(200, 20), 1, NineSliceCenterMode.Tiled)
        }

    private fun image(width: Int, height: Int): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { 0xFF426789.toInt() })
}
