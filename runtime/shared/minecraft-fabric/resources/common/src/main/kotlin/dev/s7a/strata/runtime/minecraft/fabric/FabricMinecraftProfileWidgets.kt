package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Copies the active sprite or legacy atlas widget set into detached immutable images.
 *
 * Call on the owning Minecraft client thread; all streams and native images close before return.
 * Resource selection, dimension checks, and metadata failures propagate without fallback substitution.
 *
 * @return the complete widget image set for the active adapter's GUI format.
 */
@JvmSynthetic
internal fun ResourceManager.readWidgetImages(): FabricMinecraftWidgetImages {
    val buttonPath = "textures/gui/sprites/widget/button.png"
    return if (fabricMinecraftUsesGuiSprites) {
        FabricMinecraftWidgetImages(
            checkbox = readImage("textures/gui/sprites/widget/checkbox.png", checkboxImageSize),
            checkboxHighlighted = readImage("textures/gui/sprites/widget/checkbox_highlighted.png", checkboxImageSize),
            checkboxSelected = readImage("textures/gui/sprites/widget/checkbox_selected.png", checkboxImageSize),
            checkboxSelectedHighlighted = readImage("textures/gui/sprites/widget/checkbox_selected_highlighted.png", checkboxImageSize),
            slider = readNineSliceImage("textures/gui/sprites/widget/slider.png", buttonImageSize, 1),
            sliderBorder = 1,
            sliderHighlighted = readNineSliceImage("textures/gui/sprites/widget/slider_highlighted.png", buttonImageSize, 1),
            sliderHighlightedBorder = 1,
            sliderHandle = readImage("textures/gui/sprites/widget/slider_handle.png", sliderHandleImageSize),
            sliderHandleHighlighted = readImage("textures/gui/sprites/widget/slider_handle_highlighted.png", sliderHandleImageSize),
            textFieldNormal = readImage("textures/gui/sprites/widget/text_field.png", buttonImageSize),
            textFieldHighlighted = readImage("textures/gui/sprites/widget/text_field_highlighted.png", buttonImageSize),
            buttonNormal = readNineSliceImage(buttonPath, buttonImageSize, 3),
            buttonNormalBorder = 3,
            buttonHighlighted = readNineSliceImage("textures/gui/sprites/widget/button_highlighted.png", buttonImageSize, 3),
            buttonHighlightedBorder = 3,
            buttonDisabled = readNineSliceImage("textures/gui/sprites/widget/button_disabled.png", buttonImageSize, 1),
            buttonDisabledBorder = 1,
        )
    } else {
        readLegacyWidgetImages()
    }
}

private fun ResourceManager.readLegacyWidgetImages(): FabricMinecraftWidgetImages {
    val widgets = readImage("textures/gui/widgets.png", legacyWidgetAtlasSize)
    val checkbox = readImage("textures/gui/checkbox.png", legacyCheckboxAtlasSize)
    val slider = readImage("textures/gui/slider.png", legacyWidgetAtlasSize)
    return FabricMinecraftWidgetImages(
        checkbox = checkbox.cropped(0, 0, checkboxImageSize),
        checkboxHighlighted = checkbox.cropped(20, 0, checkboxImageSize),
        checkboxSelected = checkbox.cropped(0, 20, checkboxImageSize),
        checkboxSelectedHighlighted = checkbox.cropped(20, 20, checkboxImageSize),
        slider = slider.cropped(0, 0, buttonImageSize),
        sliderBorder = LEGACY_HORIZONTAL_BORDER,
        sliderHighlighted = slider.cropped(0, 20, buttonImageSize),
        sliderHighlightedBorder = LEGACY_HORIZONTAL_BORDER,
        sliderHandle = slider.legacySliderHandle(40),
        sliderHandleHighlighted = slider.legacySliderHandle(60),
        textFieldNormal = legacyTextField(legacyTextFieldBorder),
        textFieldHighlighted = legacyTextField(opaqueMaskPixel),
        buttonNormal = widgets.cropped(0, 66, buttonImageSize),
        buttonNormalBorder = LEGACY_HORIZONTAL_BORDER,
        buttonHighlighted = widgets.cropped(0, 86, buttonImageSize),
        buttonHighlightedBorder = LEGACY_HORIZONTAL_BORDER,
        buttonDisabled = widgets.cropped(0, 46, buttonImageSize),
        buttonDisabledBorder = LEGACY_HORIZONTAL_BORDER,
    )
}

private fun DrawImage.legacySliderHandle(top: Int): DrawImage {
    val halfWidth = sliderHandleImageSize.width / 2
    val pixels = IntArray(sliderHandleImageSize.width * sliderHandleImageSize.height)
    for (y in 0 until sliderHandleImageSize.height) {
        for (x in 0 until halfWidth) {
            pixels[y * sliderHandleImageSize.width + x] = argbAt(x, top + y)
            pixels[y * sliderHandleImageSize.width + halfWidth + x] = argbAt(buttonImageSize.width - halfWidth + x, top + y)
        }
    }
    return createDrawImage(sliderHandleImageSize, pixels)
}

/**
 * Copies one atlas rectangle into a detached immutable image without changing its pixels.
 *
 * The source is read synchronously without mutation or retention; the result may outlive it.
 *
 * @param left inclusive source column.
 * @param top inclusive source row.
 * @param croppedSize dimensions of the requested rectangle.
 * @return the copied source rectangle.
 * @throws IllegalArgumentException when the rectangle extends outside the source image.
 */
@JvmSynthetic
internal fun DrawImage.cropped(
    left: Int,
    top: Int,
    croppedSize: IntSize,
): DrawImage {
    require(0 <= left && 0 <= top && left + croppedSize.width <= size.width && top + croppedSize.height <= size.height) {
        "Minecraft GUI atlas crop must remain inside the source image."
    }
    val pixels = IntArray(croppedSize.width * croppedSize.height)
    for (y in 0 until croppedSize.height) {
        for (x in 0 until croppedSize.width) {
            pixels[y * croppedSize.width + x] = argbAt(left + x, top + y)
        }
    }
    return createDrawImage(croppedSize, pixels)
}

private fun legacyTextField(border: ArgbColor): DrawImage {
    val pixels = IntArray(buttonImageSize.width * buttonImageSize.height) { legacyTextFieldBackground.value }
    for (x in 0 until buttonImageSize.width) {
        pixels[x] = border.value
        pixels[(buttonImageSize.height - 1) * buttonImageSize.width + x] = border.value
    }
    for (y in 1 until buttonImageSize.height - 1) {
        pixels[y * buttonImageSize.width] = border.value
        pixels[y * buttonImageSize.width + buttonImageSize.width - 1] = border.value
    }
    return createDrawImage(buttonImageSize, pixels)
}

private val buttonImageSize: IntSize = IntSize(200, 20)
private val checkboxImageSize: IntSize = IntSize(20, 20)
private val sliderHandleImageSize: IntSize = IntSize(8, 20)

/**
 * Immutable source dimensions shared by legacy widget and boss-bar atlas extraction.
 */
@get:JvmSynthetic
internal val legacyWidgetAtlasSize: IntSize = IntSize(256, 256)

private val legacyCheckboxAtlasSize: IntSize = IntSize(64, 64)
private const val LEGACY_HORIZONTAL_BORDER: Int = 20
private val legacyTextFieldBorder: ArgbColor = ArgbColor(0xFFA0A0A0.toInt())
private val legacyTextFieldBackground: ArgbColor = ArgbColor(0xFF000000.toInt())
