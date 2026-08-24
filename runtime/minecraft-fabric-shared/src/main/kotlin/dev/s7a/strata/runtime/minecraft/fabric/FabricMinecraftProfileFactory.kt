@file:JvmName("FabricMinecraftProfiles")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import java.io.IOException

/**
 * Extracts the complete Minecraft UI profile from the active resource manager.
 *
 * Every resource is acquired for this call only. Image streams and native images are closed after their pixels are copied, and the resulting common profile retains no Minecraft or native resource object.
 * The active resource manager may replace the vanilla images with conforming pack assets, but multi-resource font JSON stacks are rejected because this bounded extractor does not reproduce Minecraft's provider-stack merge.
 * The call belongs on the active Minecraft client thread; resource-manager and native-image failures escape without substitution.
 * The active client must use the regular bitmap font selection; the forced Unicode font option is outside this profile's verified ASCII contract.
 *
 * @return an immutable profile containing the active conforming menu, container, Slot, button, and ASCII assets.
 * @throws IllegalArgumentException when a required resource, dimension, metadata contract, or font contract is invalid.
 * @throws IllegalStateException when called away from the Minecraft client thread.
 * @throws IOException when a required resource cannot be read.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun extractMinecraftUiProfile(): MinecraftUiProfile {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread()) { "Minecraft UI profiles must be extracted on the client thread." }
    val forceUnicode =
        minecraft.options
            .forceUnicodeFont()
            .get()
    require(forceUnicode.not()) {
        "Minecraft UI profiles require the regular bitmap font selection."
    }
    val manager = minecraft.getResourceManager()
    val menu = manager.readMenuBackground()
    val containerBackground = manager.readImage("textures/gui/container/generic_54.png", IntSize(256, 256))
    val (slotHighlightBack, slotHighlightFront) = manager.readSlotHighlightImages()
    val (listBackground, headerSeparator, footerSeparator) = manager.readListDecorationImages()
    val (scrollbarBackground, scrollbarThumb) = manager.readScrollbarImages()
    val checkbox = manager.readImage("textures/gui/sprites/widget/checkbox.png", checkboxImageSize)
    val checkboxHighlighted = manager.readImage("textures/gui/sprites/widget/checkbox_highlighted.png", checkboxImageSize)
    val checkboxSelected = manager.readImage("textures/gui/sprites/widget/checkbox_selected.png", checkboxImageSize)
    val checkboxSelectedHighlighted = manager.readImage("textures/gui/sprites/widget/checkbox_selected_highlighted.png", checkboxImageSize)
    val slider = manager.readImage("textures/gui/sprites/widget/slider.png", buttonImageSize)
    val sliderHighlighted = manager.readImage("textures/gui/sprites/widget/slider_highlighted.png", buttonImageSize)
    val sliderHandle = manager.readImage("textures/gui/sprites/widget/slider_handle.png", sliderHandleImageSize)
    val sliderHandleHighlighted = manager.readImage("textures/gui/sprites/widget/slider_handle_highlighted.png", sliderHandleImageSize)
    val loadingIndicator = manager.readLoadingIndicator()
    val bundleProgressBar = manager.readBundleProgressBarOrNull()
    val legacyProgressBar = if (bundleProgressBar == null) manager.readLegacyHorizontalProgressBar() else null
    val tooltipSprites = manager.readTooltipSpritesOrNull()
    val normalTextField = manager.readImage("textures/gui/sprites/widget/text_field.png", IntSize(200, 20))
    val highlightedTextField = manager.readImage("textures/gui/sprites/widget/text_field_highlighted.png", IntSize(200, 20))
    val normal = manager.readNineSliceImage("textures/gui/sprites/widget/button.png", buttonImageSize, 3)
    val highlighted = manager.readNineSliceImage("textures/gui/sprites/widget/button_highlighted.png", buttonImageSize, 3)
    val disabled = manager.readNineSliceImage("textures/gui/sprites/widget/button_disabled.png", buttonImageSize, 1)
    val ascii = manager.readImage("textures/font/ascii.png", IntSize(128, 128))
    validateMinecraftRegularFontContract(ascii) { identifier ->
        manager.readSingleFontDocument(identifier)
    }

    return createMinecraftUiProfile {
        menuBackground(menu)
        containerBackground(containerBackground)
        slotHighlightBack(slotHighlightBack)
        slotHighlightFront(slotHighlightFront)
        listBackground(listBackground)
        listHeaderSeparator(headerSeparator)
        listFooterSeparator(footerSeparator)
        scrollbarBackground(scrollbarBackground)
        scrollbarThumb(scrollbarThumb)
        checkbox(checkbox)
        checkboxHighlighted(checkboxHighlighted)
        checkboxSelected(checkboxSelected)
        checkboxSelectedHighlighted(checkboxSelectedHighlighted)
        slider(slider)
        sliderHighlighted(sliderHighlighted)
        sliderHandle(sliderHandle)
        sliderHandleHighlighted(sliderHandleHighlighted)
        loadingIndicator(loadingIndicator)
        if (bundleProgressBar == null) {
            val (background, fill) = requireNotNull(legacyProgressBar)
            horizontalProgressBar(background, fill)
        } else {
            val (border, fill, full) = bundleProgressBar
            progressBarBorder(border)
            progressBarFill(fill)
            progressBarFull(full)
        }
        if (tooltipSprites == null) {
            legacyTooltip(legacyTooltipBackground, legacyTooltipBorderTop, legacyTooltipBorderBottom)
        } else {
            val (background, frame) = tooltipSprites
            tooltipBackground(background)
            tooltipFrame(frame)
        }
        textFieldNormal(normalTextField)
        textFieldHighlighted(highlightedTextField)
        buttonNormal(normal, 3, NineSliceCenterMode.Tiled)
        buttonHighlighted(highlighted, 3, NineSliceCenterMode.Tiled)
        buttonDisabled(disabled, 1, NineSliceCenterMode.Tiled)
        for (codePoint in printableAsciiRange) {
            printableAsciiGlyph(codePoint, extractMinecraftAsciiGlyph(ascii, codePoint))
        }
    }
}

private fun ResourceManager.readImage(
    path: String,
    expectedSize: IntSize,
): DrawImage = readImage(requiredResource(path), path, expectedSize)

private fun ResourceManager.readMenuBackground(): DrawImage {
    val currentPath = "textures/gui/menu_background.png"
    val legacyPath = "textures/gui/options_background.png"
    val current = getResource(minecraftResourceLocation("minecraft", currentPath)).orElse(null)
    return if (current == null) {
        readImage(legacyPath, IntSize(16, 16))
    } else {
        readImage(current, currentPath, IntSize(16, 16))
    }
}

private fun ResourceManager.readListDecorationImages(): Triple<DrawImage, DrawImage, DrawImage> {
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

private fun ResourceManager.readSlotHighlightImages(): Pair<DrawImage, DrawImage> {
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

private fun ResourceManager.readBundleProgressBarOrNull(): Triple<DrawImage, DrawImage, DrawImage>? {
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

private fun ResourceManager.readLegacyHorizontalProgressBar(): Pair<DrawImage, DrawImage> {
    val backgroundPath = "textures/gui/sprites/boss_bar/white_background.png"
    val fillPath = "textures/gui/sprites/boss_bar/white_progress.png"
    return Pair(
        readImage(backgroundPath, legacyProgressBarImageSize),
        readImage(fillPath, legacyProgressBarImageSize),
    )
}

private fun ResourceManager.readTooltipSpritesOrNull(): Pair<DrawImage, DrawImage>? {
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

private fun ResourceManager.readLoadingIndicator(): DrawImage {
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

private fun readImage(
    resource: Resource,
    path: String,
    expectedSize: IntSize,
): DrawImage {
    val pixels =
        resource.open().use { stream ->
            NativeImage.read(stream).use { image ->
                require(IntSize(image.getWidth(), image.getHeight()) == expectedSize) {
                    "Minecraft resource $path has an unexpected size."
                }
                copyFabricMinecraftArgbPixels(image)
            }
        }
    return createDrawImage(expectedSize, pixels)
}

private fun ResourceManager.readNineSliceImage(
    path: String,
    expectedSize: IntSize,
    expectedBorder: Int,
    expectedStretchInner: Boolean = false,
): DrawImage {
    val resource = requiredResource(path)
    val image = readImage(resource, path, expectedSize)
    val metadata =
        resource.metadata().getSection(GuiMetadataSection.TYPE).orElseThrow {
            IllegalArgumentException("Minecraft button resource $path has no GUI metadata.")
        }
    validateMinecraftNineSliceScaling(metadata.scaling(), expectedSize, expectedBorder, expectedStretchInner)
    return image
}

private fun ResourceManager.readScrollbarImage(path: String): DrawImage {
    val resource = requiredResource(path)
    val image = readImage(resource, path, scrollbarImageSize)
    val metadata =
        resource.metadata().getSection(GuiMetadataSection.TYPE).orElseThrow {
            IllegalArgumentException("Minecraft scrollbar resource $path has no GUI metadata.")
        }
    validateMinecraftScrollbarScaling(metadata.scaling())
    return image
}

private fun ResourceManager.readScrollbarImages(): Pair<DrawImage, DrawImage> {
    val backgroundPath = "textures/gui/sprites/widget/scroller_background.png"
    val backgroundResource = getResource(minecraftResourceLocation("minecraft", backgroundPath)).orElse(null)
    val background =
        if (backgroundResource == null) {
            createDrawImage(scrollbarImageSize, IntArray(scrollbarImageSize.width * scrollbarImageSize.height) { -16777216 })
        } else {
            readScrollbarImage(backgroundPath)
        }
    return Pair(background, readScrollbarImage("textures/gui/sprites/widget/scroller.png"))
}

/**
 * Copies one printable ASCII cell while enforcing the common profile's binary-white mask contract.
 *
 * The returned image owns a fresh eight-by-eight pixel snapshot and retains neither [image] nor any native resource.
 *
 * @param image immutable 128-by-128 ASCII atlas pixels.
 * @param codePoint printable ASCII code point mapped by the active atlas.
 * @return an immutable eight-by-eight binary-white glyph mask.
 * @throws IllegalArgumentException when the atlas size, code point, or any selected pixel is outside the verified contract.
 */
@JvmSynthetic
internal fun extractMinecraftAsciiGlyph(
    image: DrawImage,
    codePoint: Int,
): DrawImage {
    require(image.size == IntSize(128, 128)) { "Minecraft ASCII glyph extraction requires a 128 by 128 atlas." }
    require(codePoint in printableAsciiRange) { "Minecraft ASCII glyph extraction requires one printable ASCII code point." }
    val pixels = IntArray(64)
    val originX = (codePoint % 16) * 8
    val originY = (codePoint / 16) * 8
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val pixel = ArgbColor(image.argbAt(originX + x, originY + y))
            pixels[y * 8 + x] =
                when (pixel) {
                    transparentMaskPixel -> transparentMaskPixel.value
                    opaqueMaskPixel -> opaqueMaskPixel.value
                    else -> throw IllegalArgumentException("Minecraft ASCII glyph masks must contain only transparent or opaque white pixels.")
                }
        }
    }
    return createDrawImage(IntSize(8, 8), pixels)
}

private fun ResourceManager.requiredResource(path: String): Resource = requiredResource(minecraftResourceLocation("minecraft", path))

private fun ResourceManager.requiredResource(identifier: MinecraftResourceLocation): Resource =
    getResource(identifier).orElseThrow {
        IllegalArgumentException("Missing Minecraft resource: $identifier")
    }

private fun ResourceManager.readSingleFontDocument(identifier: MinecraftResourceLocation): String {
    val resources = getResourceStack(identifier)
    require(resources.size == 1) {
        "Minecraft font resource $identifier must come from exactly one active resource pack."
    }
    return resources.single().openAsReader().use { reader -> reader.readText() }
}

/**
 * Validates one decoded nine-slice scaling value without retaining its resource.
 *
 * @param scaling typed GUI scaling selected with the image resource.
 * @param expectedSize required logical source size.
 * @param expectedBorder required uniform border.
 * @param expectedStretchInner whether the metadata must stretch rather than tile its center.
 * @throws IllegalArgumentException when the scaling differs from the fixed supported-release resource contract.
 */
@JvmSynthetic
internal fun validateMinecraftNineSliceScaling(
    scaling: GuiSpriteScaling,
    expectedSize: IntSize,
    expectedBorder: Int,
    expectedStretchInner: Boolean,
) {
    require(scaling is GuiSpriteScaling.NineSlice) {
        "Minecraft GUI metadata must use nine-slice scaling."
    }
    require(scaling.width() == expectedSize.width) {
        "Minecraft GUI metadata has an unexpected source width."
    }
    require(scaling.height() == expectedSize.height) {
        "Minecraft GUI metadata has an unexpected source height."
    }
    val border = scaling.border()
    require(border.left() == expectedBorder && border.top() == expectedBorder && border.right() == expectedBorder && border.bottom() == expectedBorder) {
        "Minecraft GUI metadata has an unexpected border."
    }
    require(minecraftNineSliceStretchesInner(scaling) == expectedStretchInner) {
        "Minecraft GUI metadata has an unexpected center mode."
    }
}

/**
 * Validates one decoded scrollbar scaling value without retaining its resource.
 *
 * @param scaling typed GUI scaling selected with the scrollbar image resource.
 * @throws IllegalArgumentException when the scaling differs from the fixed supported-release six-by-thirty-two, one-pixel-border, tiled-center contract.
 */
@JvmSynthetic
internal fun validateMinecraftScrollbarScaling(scaling: GuiSpriteScaling) {
    require(scaling is GuiSpriteScaling.NineSlice) {
        "Minecraft scrollbar metadata must use nine-slice scaling."
    }
    require(scaling.width() == scrollbarImageSize.width) {
        "Minecraft scrollbar metadata must use a 6 pixel source width."
    }
    require(scaling.height() == scrollbarImageSize.height) {
        "Minecraft scrollbar metadata must use a 32 pixel source height."
    }
    val border = scaling.border()
    require(border.left() == 1 && border.top() == 1 && border.right() == 1 && border.bottom() == 1) {
        "Minecraft scrollbar metadata must use one-pixel borders."
    }
    require(minecraftNineSliceStretchesInner(scaling).not()) {
        "Minecraft scrollbar metadata must keep the center tiled."
    }
}

private val buttonImageSize: IntSize = IntSize(200, 20)
private val scrollbarImageSize: IntSize = IntSize(6, 32)
private val checkboxImageSize: IntSize = IntSize(20, 20)
private val sliderHandleImageSize: IntSize = IntSize(8, 20)
private val loadingIndicatorImageSize: IntSize = IntSize(5, 6)
private val slotHighlightImageSize: IntSize = IntSize(24, 24)
private val legacyProgressBarImageSize: IntSize = IntSize(182, 5)
private val printableAsciiRange: IntRange = 0x21..0x7E
private val transparentMaskPixel: ArgbColor = ArgbColor(0x00FFFFFF)
private val opaqueMaskPixel: ArgbColor = ArgbColor(-1)
private val legacySlotHighlightColor: ArgbColor = ArgbColor(0x80FFFFFF.toInt())
private val legacyTooltipBackground: ArgbColor = ArgbColor(0xF0100010.toInt())
private val legacyTooltipBorderTop: ArgbColor = ArgbColor(0x505000FF)
private val legacyTooltipBorderBottom: ArgbColor = ArgbColor(0x5028007F)
