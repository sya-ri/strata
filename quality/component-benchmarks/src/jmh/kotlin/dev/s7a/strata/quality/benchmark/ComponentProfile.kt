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
 * Explicit immutable synthetic assets for the separate component-contract corpus.
 * This profile measures actual layout/control and bitmap resource-font paths, not vanilla font loading or GPU work.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ComponentProfile {
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
            fonts(snapshot)
            buttonNormal(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonHighlighted(image(200, 20), 3, NineSliceCenterMode.Tiled)
            buttonDisabled(image(200, 20), 1, NineSliceCenterMode.Tiled)
        }

    private fun image(
        width: Int,
        height: Int,
    ): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { 0xFF426789.toInt() })
}
