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
 * Immutable prepared uniform images for independent scroll geometry and pixel checks.
 * The caller owns no native resource; the profile and detached images remain outside timed input.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ScrollInputAssets {
    /**
     * Fixed detached thumb identity for independent ordered destination checks.
     */
    internal val thumb: DrawImage = image(6, 32, 0xff151515.toInt())

    /**
     * Creates the complete profile outside timed operations; no native or application resource is acquired.
     * The unchanged default is the component bitmap snapshot; supplemental metric fixtures may supply their own snapshot.
     */
    internal fun create(snapshot: MinecraftFontSnapshot = ComponentFontAssets.snapshot()): MinecraftUiProfile =
        createMinecraftUiProfile {
            menuBackground(image(16, 16))
            containerBackground(image(256, 256))
            slotHighlightBack(image(24, 24))
            slotHighlightFront(image(24, 24))
            listBackground(image(16, 16, 0xff111111.toInt()))
            listHeaderSeparator(image(32, 2, 0xff111111.toInt()))
            listFooterSeparator(image(32, 2, 0xff111111.toInt()))
            scrollbarBackground(image(6, 32, 0xff141414.toInt()))
            scrollbarThumb(thumb)
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
            fonts(snapshot)
            buttonNormal(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonHighlighted(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonDisabled(image(200, 20), 1, NineSliceCenterMode.Tiled)
        }

    private fun image(
        width: Int,
        height: Int,
        color: Int = 0xff426789.toInt(),
    ): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { color })
}
