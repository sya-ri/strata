package dev.s7a.strata.integration.web

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Complete deterministic assets for common Minecraft and headless acceptance, without a loaded game.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ParityProfile {
    /**
     * Creates independently owned immutable presentation data.
     */
    fun create() =
        createMinecraftUiProfile {
            menuBackground(image(IntSize(16, 16)))
            containerBackground(image(IntSize(256, 256)))
            slotHighlightBack(image(IntSize(24, 24)))
            slotHighlightFront(image(IntSize(24, 24)))
            listBackground(image(IntSize(16, 16)))
            listHeaderSeparator(image(IntSize(32, 2)))
            listFooterSeparator(image(IntSize(32, 2)))
            scrollbarBackground(image(IntSize(6, 32)))
            scrollbarThumb(image(IntSize(6, 32)))
            checkbox(image(IntSize(20, 20)))
            checkboxHighlighted(image(IntSize(20, 20)))
            checkboxSelected(image(IntSize(20, 20)))
            checkboxSelectedHighlighted(image(IntSize(20, 20)))
            slider(image(IntSize(200, 20)))
            sliderHighlighted(image(IntSize(200, 20)))
            sliderHandle(image(IntSize(8, 20)))
            sliderHandleHighlighted(image(IntSize(8, 20)))
            loadingIndicator(image(IntSize(5, 6)))
            progressBarBorder(image(IntSize(12, 12)))
            progressBarFill(image(IntSize(6, 6)))
            progressBarFull(image(IntSize(6, 6)))
            tooltipBackground(image(IntSize(100, 100)))
            tooltipFrame(image(IntSize(100, 100)))
            textFieldNormal(image(IntSize(200, 20)))
            textFieldHighlighted(image(IntSize(200, 20)))
            for (codePoint in 0x21..0x7E) {
                printableAsciiGlyph(codePoint, image(IntSize(8, 8)))
            }
            val button = image(IntSize(200, 20))
            buttonNormal(button, 1, NineSliceCenterMode.Tiled)
            buttonHighlighted(button, 1, NineSliceCenterMode.Tiled)
            buttonDisabled(button, 1, NineSliceCenterMode.Tiled)
        }

    private fun image(size: IntSize) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { 0xFFFFFFFF.toInt() })
}
