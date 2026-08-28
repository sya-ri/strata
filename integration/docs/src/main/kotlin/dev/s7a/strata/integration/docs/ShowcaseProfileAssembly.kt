@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfileBuilder
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Assembles the complete 26.2 profile from validated original images and a detached font graph.
 * The callback owns no native resource; the result retains immutable images and snapshot only.
 */
internal fun createShowcaseMinecraftProfile(
    snapshot: MinecraftFontSnapshot,
    images: Map<ShowcaseGuiAsset, DrawImage>,
): MinecraftUiProfile =
    createMinecraftUiProfile {
        fonts(snapshot)
        decorations(images)
        widgets(images)
    }

private fun MinecraftUiProfileBuilder.decorations(images: Map<ShowcaseGuiAsset, DrawImage>) {
    menuBackground(images.getValue(ShowcaseGuiAsset.MenuBackground))
    containerBackground(images.getValue(ShowcaseGuiAsset.ContainerBackground))
    slotHighlightBack(images.getValue(ShowcaseGuiAsset.SlotHighlightBack))
    slotHighlightFront(images.getValue(ShowcaseGuiAsset.SlotHighlightFront))
    listBackground(images.getValue(ShowcaseGuiAsset.ListBackground))
    listHeaderSeparator(images.getValue(ShowcaseGuiAsset.HeaderSeparator))
    listFooterSeparator(images.getValue(ShowcaseGuiAsset.FooterSeparator))
    scrollbarBackground(images.getValue(ShowcaseGuiAsset.ScrollbarBackground))
    scrollbarThumb(images.getValue(ShowcaseGuiAsset.ScrollbarThumb))
    loadingIndicator(images.getValue(ShowcaseGuiAsset.LoadingIndicator))
    progressBarBorder(images.getValue(ShowcaseGuiAsset.ProgressBarBorder))
    progressBarFill(images.getValue(ShowcaseGuiAsset.ProgressBarFill))
    progressBarFull(images.getValue(ShowcaseGuiAsset.ProgressBarFull))
    tooltipBackground(images.getValue(ShowcaseGuiAsset.TooltipBackground))
    tooltipFrame(images.getValue(ShowcaseGuiAsset.TooltipFrame))
}

private fun MinecraftUiProfileBuilder.widgets(images: Map<ShowcaseGuiAsset, DrawImage>) {
    checkbox(images.getValue(ShowcaseGuiAsset.Checkbox))
    checkboxHighlighted(images.getValue(ShowcaseGuiAsset.CheckboxHighlighted))
    checkboxSelected(images.getValue(ShowcaseGuiAsset.CheckboxSelected))
    checkboxSelectedHighlighted(images.getValue(ShowcaseGuiAsset.CheckboxSelectedHighlighted))
    slider(images.getValue(ShowcaseGuiAsset.Slider), 1, NineSliceCenterMode.Tiled)
    sliderHighlighted(images.getValue(ShowcaseGuiAsset.SliderHighlighted), 1, NineSliceCenterMode.Tiled)
    sliderHandle(images.getValue(ShowcaseGuiAsset.SliderHandle))
    sliderHandleHighlighted(images.getValue(ShowcaseGuiAsset.SliderHandleHighlighted))
    textFieldNormal(images.getValue(ShowcaseGuiAsset.TextField))
    textFieldHighlighted(images.getValue(ShowcaseGuiAsset.TextFieldHighlighted))
    buttonNormal(images.getValue(ShowcaseGuiAsset.Button), 3, NineSliceCenterMode.Tiled)
    buttonHighlighted(images.getValue(ShowcaseGuiAsset.ButtonHighlighted), 3, NineSliceCenterMode.Tiled)
    buttonDisabled(images.getValue(ShowcaseGuiAsset.ButtonDisabled), 1, NineSliceCenterMode.Tiled)
}
