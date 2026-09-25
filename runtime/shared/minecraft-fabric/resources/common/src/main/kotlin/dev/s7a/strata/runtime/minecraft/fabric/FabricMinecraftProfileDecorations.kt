package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Copies list backgrounds and separators, retaining the legacy separator fallback.
 *
 * Call on the owning Minecraft client thread; returned images retain no resource objects.
 * Required image acquisition or validation failures propagate to the profile caller.
 *
 * @return the background, header separator, and footer separator in that order.
 */
@JvmSynthetic
internal fun ResourceManager.readListDecorationImages(): Triple<DrawImage, DrawImage, DrawImage> {
    val currentBackgroundPath = "textures/gui/menu_list_background.png"
    val currentBackground = getResource(minecraftResourceLocation("minecraft", currentBackgroundPath)).orElse(null)
    if (currentBackground != null) {
        return Triple(
            readImage(currentBackground, currentBackgroundPath, IntSize(16, 16)),
            readImage("textures/gui/header_separator.png", IntSize(32, 2)),
            readImage("textures/gui/footer_separator.png", IntSize(32, 2)),
        )
    }
    val legacyBackground = readImage("textures/gui/options_background.png", IntSize(16, 16))
    val legacyFooter = readImage("textures/gui/footer_separator.png", IntSize(32, 2))
    val legacyHeaderPath = "textures/gui/header_separator.png"
    val legacyHeaderResource = getResource(minecraftResourceLocation("minecraft", legacyHeaderPath)).orElse(null)
    val legacyHeader =
        if (legacyHeaderResource == null) {
            legacyFooter.verticallyFlipped()
        } else {
            readImage(legacyHeaderResource, legacyHeaderPath, IntSize(32, 2))
        }
    return Triple(legacyBackground, legacyHeader, legacyFooter)
}

private fun DrawImage.verticallyFlipped(): DrawImage {
    val pixels = IntArray(size.width * size.height)
    for (y in 0 until size.height) {
        val sourceY = size.height - y - 1
        for (x in 0 until size.width) {
            pixels[y * size.width + x] = argbAt(x, sourceY)
        }
    }
    return createDrawImage(size, pixels)
}

/**
 * Copies the paired slot-highlight sprites or constructs the legacy highlight pair.
 *
 * Call on the owning Minecraft client thread; returned images retain no resource objects.
 * Present sprite failures propagate rather than selecting the legacy fallback.
 *
 * @return detached back and front highlight images.
 * @throws IllegalArgumentException when only one sprite is present or either image has the wrong size.
 */
@JvmSynthetic
internal fun ResourceManager.readSlotHighlightImages(): Pair<DrawImage, DrawImage> {
    val backPath = "textures/gui/sprites/container/slot_highlight_back.png"
    val frontPath = "textures/gui/sprites/container/slot_highlight_front.png"
    val back = getResource(minecraftResourceLocation("minecraft", backPath)).orElse(null)
    val front = getResource(minecraftResourceLocation("minecraft", frontPath)).orElse(null)
    require((back == null) == (front == null)) {
        "Minecraft Slot highlight resources must provide both the back and front layers."
    }
    return if (back == null) {
        legacySlotHighlightImages()
    } else {
        Pair(
            readImage(back, backPath, slotHighlightImageSize),
            readImage(requireNotNull(front), frontPath, slotHighlightImageSize),
        )
    }
}

private fun legacySlotHighlightImages(): Pair<DrawImage, DrawImage> {
    val frontPixels = IntArray(slotHighlightImageSize.width * slotHighlightImageSize.height)
    for (y in 4 until 20) {
        for (x in 4 until 20) {
            frontPixels[y * slotHighlightImageSize.width + x] = legacySlotHighlightColor.value
        }
    }
    return Pair(
        createDrawImage(slotHighlightImageSize, IntArray(frontPixels.size)),
        createDrawImage(slotHighlightImageSize, frontPixels),
    )
}

/**
 * Copies the complete bundle progress sprite set when it is present.
 *
 * Call on the owning Minecraft client thread; all acquired resources close before return.
 * Image and metadata failures propagate without replacing an invalid present set.
 *
 * @return detached border, fill, and full images, or null when all three sprites are absent.
 * @throws IllegalArgumentException when only part of the set is present or its contract is invalid.
 */
@JvmSynthetic
internal fun ResourceManager.readBundleProgressBarOrNull(): Triple<DrawImage, DrawImage, DrawImage>? {
    val borderPath = "textures/gui/sprites/container/bundle/bundle_progressbar_border.png"
    val fillPath = "textures/gui/sprites/container/bundle/bundle_progressbar_fill.png"
    val fullPath = "textures/gui/sprites/container/bundle/bundle_progressbar_full.png"
    val resources =
        listOf(borderPath, fillPath, fullPath).map { path ->
            getResource(minecraftResourceLocation("minecraft", path)).orElse(null)
        }
    require(resources.all { resource -> resource == null } || resources.all { resource -> resource != null }) {
        "Minecraft bundle ProgressBar resources must provide the border, fill, and completed fill together."
    }
    if (resources.first() == null) return null
    return Triple(
        readNineSliceImage(borderPath, IntSize(12, 12), 2),
        readNineSliceImage(fillPath, IntSize(6, 6), 2),
        readNineSliceImage(fullPath, IntSize(6, 6), 2),
    )
}

/**
 * Copies the legacy horizontal progress images from separate sprites or the boss-bar atlas.
 *
 * Call on the owning Minecraft client thread; returned images retain no resource objects.
 * Required resource and image validation failures propagate to the profile caller.
 *
 * @return detached background and fill images in that order.
 */
@JvmSynthetic
internal fun ResourceManager.readLegacyHorizontalProgressBar(): Pair<DrawImage, DrawImage> {
    val backgroundPath = "textures/gui/sprites/boss_bar/white_background.png"
    val fillPath = "textures/gui/sprites/boss_bar/white_progress.png"
    if (getResource(minecraftResourceLocation("minecraft", backgroundPath)).isEmpty) {
        val bars = readImage("textures/gui/bars.png", legacyWidgetAtlasSize)
        return Pair(
            bars.cropped(0, 60, legacyProgressBarImageSize),
            bars.cropped(0, 65, legacyProgressBarImageSize),
        )
    }
    return Pair(
        readImage(backgroundPath, legacyProgressBarImageSize),
        readImage(fillPath, legacyProgressBarImageSize),
    )
}

/**
 * Copies the paired tooltip sprites when both are present and satisfy their metadata contract.
 *
 * Call on the owning Minecraft client thread; returned images retain no resource objects.
 * Present resource and metadata failures propagate without selecting a legacy tooltip.
 *
 * @return detached background and frame images, or null when both are absent.
 * @throws IllegalArgumentException when only one sprite is present or either contract is invalid.
 */
@JvmSynthetic
internal fun ResourceManager.readTooltipSpritesOrNull(): Pair<DrawImage, DrawImage>? {
    val backgroundPath = "textures/gui/sprites/tooltip/background.png"
    val framePath = "textures/gui/sprites/tooltip/frame.png"
    val background = getResource(minecraftResourceLocation("minecraft", backgroundPath)).orElse(null)
    val frame = getResource(minecraftResourceLocation("minecraft", framePath)).orElse(null)
    require((background == null) == (frame == null)) {
        "Minecraft tooltip resources must provide both the background and frame sprites."
    }
    if (background == null) return null
    return Pair(
        readNineSliceImage(backgroundPath, IntSize(100, 100), 9),
        readNineSliceImage(
            framePath,
            IntSize(100, 100),
            10,
            expectedStretchInner = minecraftTooltipFrameStretchesInner,
        ),
    )
}

/**
 * Copies the loading strip or constructs the legacy strip when the resource is absent.
 *
 * Call on the owning Minecraft client thread; returned pixels retain no resource objects.
 * A present resource's read or size failures propagate to the profile caller.
 *
 * @return the detached loading animation strip.
 */
@JvmSynthetic
internal fun ResourceManager.readLoadingIndicator(): DrawImage {
    val path = "textures/gui/sprites/friends/loading.png"
    val resource = getResource(minecraftResourceLocation("minecraft", path)).orElse(null)
    return if (resource == null) legacyLoadingIndicator() else readImage(resource, path, loadingIndicatorImageSize)
}

private fun legacyLoadingIndicator(): DrawImage {
    val pixels = IntArray(loadingIndicatorImageSize.width * loadingIndicatorImageSize.height)
    for (frame in 0 until 3) {
        val row = frame * 2
        for (dot in 0..frame) {
            pixels[row * loadingIndicatorImageSize.width + dot * 2] = -1
        }
    }
    return createDrawImage(loadingIndicatorImageSize, pixels)
}

/**
 * Copies the scrollbar sprite pair, constructing each missing legacy image independently.
 *
 * Call on the owning Minecraft client thread; returned images retain no resource objects.
 * Present resource and metadata failures propagate to the profile caller.
 *
 * @return detached background and thumb images in that order.
 */
@JvmSynthetic
internal fun ResourceManager.readScrollbarImages(): Pair<DrawImage, DrawImage> {
    val backgroundPath = "textures/gui/sprites/widget/scroller_background.png"
    val backgroundResource = getResource(minecraftResourceLocation("minecraft", backgroundPath)).orElse(null)
    val background =
        if (backgroundResource == null) {
            createDrawImage(scrollbarImageSize, IntArray(scrollbarImageSize.width * scrollbarImageSize.height) { -16777216 })
        } else {
            readScrollbarImage(backgroundPath)
        }
    val thumbPath = "textures/gui/sprites/widget/scroller.png"
    val thumb =
        if (getResource(minecraftResourceLocation("minecraft", thumbPath)).isEmpty) {
            legacyScrollbarThumb()
        } else {
            readScrollbarImage(thumbPath)
        }
    return Pair(background, thumb)
}

private fun legacyScrollbarThumb(): DrawImage {
    val pixels = IntArray(scrollbarImageSize.width * scrollbarImageSize.height) { legacyScrollbarBorder.value }
    for (y in 0 until scrollbarImageSize.height - 1) {
        for (x in 0 until scrollbarImageSize.width - 1) {
            pixels[y * scrollbarImageSize.width + x] = legacyScrollbarInner.value
        }
    }
    return createDrawImage(scrollbarImageSize, pixels)
}

private val loadingIndicatorImageSize: IntSize = IntSize(5, 6)
private val slotHighlightImageSize: IntSize = IntSize(24, 24)
private val legacyProgressBarImageSize: IntSize = IntSize(182, 5)
private val legacySlotHighlightColor: ArgbColor = ArgbColor(0x80FFFFFF.toInt())
private val legacyScrollbarBorder: ArgbColor = ArgbColor(0xFF808080.toInt())
private val legacyScrollbarInner: ArgbColor = ArgbColor(0xFFC0C0C0.toInt())
