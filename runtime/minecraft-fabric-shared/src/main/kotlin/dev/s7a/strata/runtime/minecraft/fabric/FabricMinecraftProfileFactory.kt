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
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import java.io.IOException

/**
 * Extracts the complete Minecraft UI profile from the active resource manager.
 *
 * Every resource is acquired for this call only. Image streams and native images are closed after their pixels are copied, and the resulting common profile retains no Minecraft or native resource object.
 * Active resource-pack font stacks, custom font files, and language font options are copied into an immutable font snapshot.
 * The call belongs on the active Minecraft client thread; resource-manager and native-image failures escape without substitution.
 *
 * @return an immutable profile containing the active conforming GUI assets and resource fonts.
 * @throws IllegalArgumentException when a required resource, dimension, metadata contract, or font contract is invalid.
 * @throws IllegalStateException when called away from the Minecraft client thread.
 * @throws IOException when a required resource cannot be read.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun extractMinecraftUiProfile(): MinecraftUiProfile {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread()) { "Minecraft UI profiles must be extracted on the client thread." }
    val manager = minecraft.getResourceManager()
    val menu = manager.readMenuBackground()
    val containerBackground = manager.readImage("textures/gui/container/generic_54.png", IntSize(256, 256))
    val (slotHighlightBack, slotHighlightFront) = manager.readSlotHighlightImages()
    val (listBackground, headerSeparator, footerSeparator) = manager.readListDecorationImages()
    val (scrollbarBackground, scrollbarThumb) = manager.readScrollbarImages()
    val widgets = manager.readWidgetImages()
    val loadingIndicator = manager.readLoadingIndicator()
    val bundleProgressBar = manager.readBundleProgressBarOrNull()
    val legacyProgressBar = if (bundleProgressBar == null) manager.readLegacyHorizontalProgressBar() else null
    val tooltipSprites = manager.readTooltipSpritesOrNull()
    val fontSnapshot = extractFabricMinecraftFontSnapshot(minecraft)

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
        checkbox(widgets.checkbox)
        checkboxHighlighted(widgets.checkboxHighlighted)
        checkboxSelected(widgets.checkboxSelected)
        checkboxSelectedHighlighted(widgets.checkboxSelectedHighlighted)
        slider(widgets.slider, widgets.sliderBorder, NineSliceCenterMode.Tiled)
        sliderHighlighted(widgets.sliderHighlighted, widgets.sliderHighlightedBorder, NineSliceCenterMode.Tiled)
        sliderHandle(widgets.sliderHandle)
        sliderHandleHighlighted(widgets.sliderHandleHighlighted)
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
        textFieldNormal(widgets.textFieldNormal)
        textFieldHighlighted(widgets.textFieldHighlighted)
        buttonNormal(widgets.buttonNormal, widgets.buttonNormalBorder, NineSliceCenterMode.Tiled)
        buttonHighlighted(widgets.buttonHighlighted, widgets.buttonHighlightedBorder, NineSliceCenterMode.Tiled)
        buttonDisabled(widgets.buttonDisabled, widgets.buttonDisabledBorder, NineSliceCenterMode.Tiled)
        fonts(fontSnapshot)
    }
}

/**
 * Reads a required image from this manager and copies its validated dimensions and pixels.
 *
 * Call on the owning Minecraft client thread; streams and native images close before return.
 * Resource acquisition and decoding failures propagate without substitution.
 *
 * @param path resource path in the Minecraft namespace.
 * @param expectedSize required dimensions of the image.
 * @return detached immutable straight-ARGB pixels.
 * @throws IllegalArgumentException when the resource is absent or has different dimensions.
 */
@JvmSynthetic
internal fun ResourceManager.readImage(
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

/**
 * Decodes one selected resource into detached immutable pixels of the required dimensions.
 *
 * Call on the owning Minecraft client thread; the stream and native image close before return or failure.
 *
 * @param resource selected resource opened for this call only.
 * @param path resource path used in validation diagnostics.
 * @param expectedSize required image dimensions.
 * @return copied straight-ARGB pixels without native ownership.
 * @throws IllegalArgumentException when the decoded dimensions differ from the required size.
 * @throws IOException when the resource cannot be read or decoded.
 */
@JvmSynthetic
internal fun readImage(
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

/**
 * Copies a required sprite after validating its dimensions and nine-slice metadata.
 *
 * Call on the owning Minecraft client thread; the result retains no resource or native image.
 * Read and metadata decoding failures propagate without substitution.
 *
 * @param path resource path in the Minecraft namespace.
 * @param expectedSize required sprite dimensions.
 * @param expectedBorder required width of all four borders.
 * @param expectedStretchInner required center stretch flag.
 * @return detached immutable sprite pixels.
 * @throws IllegalArgumentException when the resource is absent or its image or metadata contract is invalid.
 */
@JvmSynthetic
internal fun ResourceManager.readNineSliceImage(
    path: String,
    expectedSize: IntSize,
    expectedBorder: Int,
    expectedStretchInner: Boolean = false,
): DrawImage {
    val resource = requiredResource(path)
    val image = readImage(resource, path, expectedSize)
    val scaling = readFabricMinecraftGuiScaling(resource, path)
    validateMinecraftNineSliceScaling(scaling, expectedSize, expectedBorder, expectedStretchInner)
    return image
}

/**
 * Copies a required scrollbar sprite after validating its dimensions and scaling metadata.
 *
 * Call on the owning Minecraft client thread; the result retains no resource or native image.
 * Read and metadata decoding failures propagate without substitution.
 *
 * @param path resource path in the Minecraft namespace.
 * @return detached immutable scrollbar pixels.
 * @throws IllegalArgumentException when the resource is absent or violates the scrollbar contract.
 */
@JvmSynthetic
internal fun ResourceManager.readScrollbarImage(path: String): DrawImage {
    val resource = requiredResource(path)
    val image = readImage(resource, path, scrollbarImageSize)
    validateMinecraftScrollbarScaling(readFabricMinecraftGuiScaling(resource, path))
    return image
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
    scaling: FabricMinecraftGuiScaling,
    expectedSize: IntSize,
    expectedBorder: Int,
    expectedStretchInner: Boolean,
) {
    require(scaling.width == expectedSize.width) {
        "Minecraft GUI metadata has an unexpected source width."
    }
    require(scaling.height == expectedSize.height) {
        "Minecraft GUI metadata has an unexpected source height."
    }
    require(
        scaling.borderLeft == expectedBorder &&
            scaling.borderTop == expectedBorder &&
            scaling.borderRight == expectedBorder &&
            scaling.borderBottom == expectedBorder,
    ) {
        "Minecraft GUI metadata has an unexpected border."
    }
    require(scaling.stretchesInner == expectedStretchInner) {
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
internal fun validateMinecraftScrollbarScaling(scaling: FabricMinecraftGuiScaling) {
    require(scaling.width == scrollbarImageSize.width) {
        "Minecraft scrollbar metadata must use a 6 pixel source width."
    }
    require(scaling.height == scrollbarImageSize.height) {
        "Minecraft scrollbar metadata must use a 32 pixel source height."
    }
    require(scaling.borderLeft == 1 && scaling.borderTop == 1 && scaling.borderRight == 1 && scaling.borderBottom == 1) {
        "Minecraft scrollbar metadata must use one-pixel borders."
    }
    require(scaling.stretchesInner.not()) {
        "Minecraft scrollbar metadata must keep the center tiled."
    }
}

/**
 * Immutable scrollbar dimensions shared by resource validation and legacy image construction.
 */
@get:JvmSynthetic
internal val scrollbarImageSize: IntSize = IntSize(6, 32)

private val printableAsciiRange: IntRange = 0x21..0x7E
private val transparentMaskPixel: ArgbColor = ArgbColor(0x00FFFFFF)

/**
 * Immutable opaque-white pixel shared by legacy glyph validation and focused text-field borders.
 */
@get:JvmSynthetic
internal val opaqueMaskPixel: ArgbColor = ArgbColor(-1)

private val legacyTooltipBackground: ArgbColor = ArgbColor(0xF0100010.toInt())
private val legacyTooltipBorderTop: ArgbColor = ArgbColor(0x505000FF)
private val legacyTooltipBorderBottom: ArgbColor = ArgbColor(0x5028007F)
