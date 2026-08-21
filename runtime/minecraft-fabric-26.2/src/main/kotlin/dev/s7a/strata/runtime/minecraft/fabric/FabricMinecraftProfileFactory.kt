@file:JvmName("FabricMinecraftProfiles")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftNineSliceCenterMode
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import java.io.IOException

/**
 * Extracts the complete 26.2 Minecraft UI profile from the active resource manager.
 *
 * Every resource is acquired for this call only. Image streams and native images are closed after their pixels are copied, and the resulting common profile retains no Minecraft or native resource object.
 * The active resource manager may replace the vanilla images with conforming pack assets, but multi-resource font JSON stacks are rejected because this bounded extractor does not reproduce Minecraft's provider-stack merge.
 * The call belongs on the active Minecraft client thread; resource-manager and native-image failures escape without substitution.
 * The active client must use the regular bitmap font selection; the forced Unicode font option is outside this profile's verified ASCII contract.
 *
 * @return an immutable profile containing the active conforming menu, button, and ASCII assets.
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
    val menu = manager.readImage("textures/gui/menu_background.png", IntSize(16, 16))
    val normal = manager.readNineSliceImage("textures/gui/sprites/widget/button.png", 3)
    val highlighted = manager.readNineSliceImage("textures/gui/sprites/widget/button_highlighted.png", 3)
    val disabled = manager.readNineSliceImage("textures/gui/sprites/widget/button_disabled.png", 1)
    val ascii = manager.readImage("textures/font/ascii.png", IntSize(128, 128))
    validateMinecraftRegularFontContract(ascii) { identifier ->
        manager.readSingleFontDocument(identifier)
    }

    return createMinecraftUiProfile {
        menuBackground(menu)
        buttonNormal(normal, 3, MinecraftNineSliceCenterMode.Tiled)
        buttonHighlighted(highlighted, 3, MinecraftNineSliceCenterMode.Tiled)
        buttonDisabled(disabled, 1, MinecraftNineSliceCenterMode.Tiled)
        for (codePoint in printableAsciiRange) {
            printableAsciiGlyph(codePoint, extractMinecraftAsciiGlyph(ascii, codePoint))
        }
    }
}

private fun ResourceManager.readImage(
    path: String,
    expectedSize: IntSize,
): DrawImage = readImage(requiredResource(path), path, expectedSize)

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
                image.getPixels()
            }
        }
    return createDrawImage(expectedSize, pixels)
}

private fun ResourceManager.readNineSliceImage(
    path: String,
    expectedBorder: Int,
): DrawImage {
    val resource = requiredResource(path)
    val image = readImage(resource, path, buttonImageSize)
    val metadata =
        resource.metadata().getSection(GuiMetadataSection.TYPE).orElseThrow {
            IllegalArgumentException("Minecraft button resource $path has no GUI metadata.")
        }
    validateMinecraftNineSliceScaling(metadata.scaling(), expectedBorder)
    return image
}

/**
 * Copies one printable ASCII cell while enforcing the common profile's binary-white mask contract.
 *
 * The returned image owns a fresh eight-by-eight pixel snapshot and retains neither [image] nor any native resource.
 *
 * @param image immutable 128-by-128 ASCII atlas pixels.
 * @param codePoint printable ASCII code point mapped by the 26.2 atlas.
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

private fun ResourceManager.requiredResource(path: String): Resource = requiredResource(Identifier.fromNamespaceAndPath("minecraft", path))

private fun ResourceManager.requiredResource(identifier: Identifier): Resource =
    getResource(identifier).orElseThrow {
        IllegalArgumentException("Missing Minecraft resource: $identifier")
    }

private fun ResourceManager.readSingleFontDocument(identifier: Identifier): String {
    val resources = getResourceStack(identifier)
    require(resources.size == 1) {
        "Minecraft font resource $identifier must come from exactly one active resource pack."
    }
    return resources.single().openAsReader().use { reader -> reader.readText() }
}

/**
 * Validates one decoded 26.2 button scaling value without retaining its resource.
 *
 * @param scaling typed GUI scaling selected with the button image resource.
 * @param expectedBorder border required by the corresponding vanilla button variant.
 * @throws IllegalArgumentException when the scaling differs from the fixed 26.2 button contract.
 */
@JvmSynthetic
internal fun validateMinecraftNineSliceScaling(
    scaling: GuiSpriteScaling,
    expectedBorder: Int,
) {
    require(scaling is GuiSpriteScaling.NineSlice) {
        "Minecraft button metadata must use nine-slice scaling."
    }
    require(scaling.width() == buttonImageSize.width) {
        "Minecraft button metadata must use a 200 pixel source width."
    }
    require(scaling.height() == buttonImageSize.height) {
        "Minecraft button metadata must use a 20 pixel source height."
    }
    val border = scaling.border()
    require(border.left() == expectedBorder && border.top() == expectedBorder && border.right() == expectedBorder && border.bottom() == expectedBorder) {
        "Minecraft button metadata has an unexpected border."
    }
    require(scaling.stretchInner().not()) {
        "Minecraft button metadata must keep the center tiled."
    }
}

private val buttonImageSize: IntSize = IntSize(200, 20)
private val printableAsciiRange: IntRange = 0x21..0x7E
private val transparentMaskPixel: ArgbColor = ArgbColor(0x00FFFFFF)
private val opaqueMaskPixel: ArgbColor = ArgbColor(-1)
