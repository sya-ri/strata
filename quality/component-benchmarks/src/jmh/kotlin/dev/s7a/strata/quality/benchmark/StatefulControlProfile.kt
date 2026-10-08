package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Complete immutable compatibility profile whose original pixels and glyph arithmetic form the independent oracle.
 * Assets are prepared outside collection and acquire no Minecraft, native font or graphics resource.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object StatefulControlProfile {
    /**
     * Creates a fresh profile owner with stable references throughout a trial.
     */
    fun create(): MinecraftUiProfile =
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
            checkboxHighlighted(image(20, 20, 0xFF203040.toInt()))
            checkboxSelected(image(20, 20, 0xFF304050.toInt()))
            checkboxSelectedHighlighted(image(20, 20, 0xFF405060.toInt()))
            slider(image(200, 20), 1, NineSliceCenterMode.Tiled)
            sliderHighlighted(image(200, 20, 0xFF203040.toInt()), 1, NineSliceCenterMode.Tiled)
            sliderHandle(image(8, 20, 0xFF405060.toInt()))
            sliderHandleHighlighted(image(8, 20, 0xFF506070.toInt()))
            loadingIndicator(image(5, 6))
            progressBarBorder(image(12, 12))
            progressBarFill(image(6, 6))
            progressBarFull(image(6, 6))
            tooltipBackground(image(100, 100))
            tooltipFrame(image(100, 100))
            textFieldNormal(image(200, 20))
            textFieldHighlighted(image(200, 20))
            val glyph = createDrawImage(IntSize(8, 8), IntArray(64) { index -> if (index == 0) -1 else 0x00FFFFFF })
            for (codePoint in 0x21..0x7E) printableAsciiGlyph(codePoint, glyph)
            buttonNormal(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonHighlighted(image(200, 20, 0xFF203040.toInt()), 3, NineSliceCenterMode.Tiled)
            buttonDisabled(image(200, 20, 0xFF506070.toInt()), 1, NineSliceCenterMode.Tiled)
        }

    /**
     * Creates opaque synthetic pixels with explicit source dimensions.
     */
    fun image(
        width: Int,
        height: Int,
        color: Int = 0xFF102030.toInt(),
    ): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { color })
}
