package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Callback-lifetime builder for one complete Minecraft UI profile.
 *
 * The builder is confined to its creator thread and rejects use after the profile callback returns.
 * Menu, container, Slot, Scroll, TextField, and Button declarations retain immutable image references, while glyph declarations synchronously copy a binary mask into nine derived immutable layers and retain no mask reference.
 * Every method rejects duplicate slots.
 */
@InternalStrataRuntimeApi
// Why: one callback must declare every required asset slot atomically before a complete immutable profile can exist.
@Suppress("TooManyFunctions")
public sealed interface MinecraftUiProfileBuilder {
    /**
     * Supplies immutable resource-pack font definitions and files instead of the compatibility ASCII mask declarations.
     * The snapshot can be shared between profiles and hosts and owns no native state.
     * A host using this profile must supply its independently owned font backend through the font-aware host factory.
     *
     * @param snapshot complete immutable font resource state, pinned until a new profile and host are created.
     * @throws IllegalArgumentException when fonts or any compatibility glyph were already declared.
     * @throws IllegalStateException after the callback returns or from another thread.
     */
    public fun fonts(snapshot: MinecraftFontSnapshot)

    /**
     * Supplies the exact 100 by 100 tooltip background sprite.
     *
     * @param image immutable nine-slice background pixels.
     */
    public fun tooltipBackground(image: DrawImage)

    /**
     * Supplies the exact 100 by 100 tooltip frame sprite.
     *
     * @param image immutable stretched-center nine-slice frame pixels.
     */
    public fun tooltipFrame(image: DrawImage)

    /**
     * Supplies the code-defined tooltip colors used by Minecraft releases before tooltip sprites existed.
     *
     * This declaration is an alternative to [tooltipBackground] and [tooltipFrame].
     *
     * @param backgroundColor native tooltip fill and outside-edge color.
     * @param borderTop native top border color.
     * @param borderBottom native bottom border color.
     * @throws IllegalArgumentException when sprite-backed tooltip assets or legacy colors were already declared.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun legacyTooltip(
        backgroundColor: ArgbColor,
        borderTop: ArgbColor,
        borderBottom: ArgbColor,
    )

    /**
     * Supplies the exact 12 by 12 bundle progress-bar border sprite.
     *
     * @param image immutable nine-slice border pixels.
     */
    public fun progressBarBorder(image: DrawImage)

    /**
     * Supplies the exact 6 by 6 bundle progress-bar fill sprite.
     *
     * @param image immutable nine-slice fill pixels.
     */
    public fun progressBarFill(image: DrawImage)

    /**
     * Supplies the exact 6 by 6 completed bundle progress-bar fill sprite.
     *
     * @param image immutable nine-slice completed-fill pixels.
     */
    public fun progressBarFull(image: DrawImage)

    /**
     * Supplies one fixed-source horizontal progress-bar background and fill pair.
     *
     * This declaration is an alternative to the three bundle progress-bar declarations and preserves older Minecraft releases whose native progress treatment uses equal-sized sprites rather than bundle nine-slices.
     * The runtime scales the complete background to the requested component size and crops the fill horizontally by the normalized progress value.
     *
     * @param background immutable nonempty background sprite.
     * @param fill immutable fill sprite with the same size as [background].
     * @throws IllegalArgumentException when either image is empty, their sizes differ, or any bundle progress-bar sprite was already declared.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun horizontalProgressBar(
        background: DrawImage,
        fill: DrawImage,
    )

    /**
     * Supplies the exact 5 by 6 three-frame friends loading sprite sheet.
     *
     * @param image immutable vertically stacked animation pixels.
     */
    public fun loadingIndicator(image: DrawImage)

    /**
     * Supplies the profile's exact 16 by 16 Minecraft menu texture.
     *
     * @param image immutable menu-background pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 16 by 16.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun menuBackground(image: DrawImage)

    /**
     * Supplies the exact 256 by 256 generic-container texture used by the active vanilla chest screens.
     *
     * @param image immutable generic-container pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 256 by 256.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun containerBackground(image: DrawImage)

    /**
     * Supplies the exact 24 by 24 back layer painted behind a highlighted container Slot.
     *
     * @param image immutable back-highlight pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 24 by 24.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun slotHighlightBack(image: DrawImage)

    /**
     * Supplies the exact 24 by 24 front layer painted over a highlighted container Slot.
     *
     * @param image immutable front-highlight pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 24 by 24.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun slotHighlightFront(image: DrawImage)

    /**
     * Supplies the exact 16 by 16 menu-list background texture.
     *
     * @param image immutable list-background pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 16 by 16.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun listBackground(image: DrawImage)

    /**
     * Supplies the exact 32 by 2 menu-list header separator texture.
     *
     * @param image immutable header-separator pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 32 by 2.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun listHeaderSeparator(image: DrawImage)

    /**
     * Supplies the exact 32 by 2 menu-list footer separator texture.
     *
     * @param image immutable footer-separator pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 32 by 2.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun listFooterSeparator(image: DrawImage)

    /**
     * Supplies the exact 6 by 32 tiled nine-slice scrollbar-track sprite.
     *
     * The runtime uses the verified one-pixel border and tiled center policy.
     *
     * @param image immutable scrollbar-track pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 6 by 32.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun scrollbarBackground(image: DrawImage)

    /**
     * Supplies the exact 6 by 32 tiled nine-slice scrollbar-thumb sprite.
     *
     * The runtime uses the verified one-pixel border and tiled center policy.
     *
     * @param image immutable scrollbar-thumb pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 6 by 32.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun scrollbarThumb(image: DrawImage)

    /**
     * Supplies the exact 20 by 20 unselected Checkbox sprite.
     */
    public fun checkbox(image: DrawImage)

    /**
     * Supplies the exact 20 by 20 hovered unselected Checkbox sprite.
     */
    public fun checkboxHighlighted(image: DrawImage)

    /**
     * Supplies the exact 20 by 20 selected Checkbox sprite.
     */
    public fun checkboxSelected(image: DrawImage)

    /**
     * Supplies the exact 20 by 20 hovered selected Checkbox sprite.
     */
    public fun checkboxSelectedHighlighted(image: DrawImage)

    /**
     * Supplies the exact 200 by 20 normal Slider track sprite with a one-pixel tiled horizontal border.
     */
    public fun slider(image: DrawImage)

    /**
     * Supplies the exact 200 by 20 normal Slider track sprite and its horizontal sampling policy.
     *
     * @param image immutable track pixels.
     * @param border positive horizontal border width that leaves a nonempty source center.
     * @param centerMode typed center sampling policy.
     * @throws IllegalArgumentException when the slot, image size, or border is invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun slider(
        image: DrawImage,
        border: Int,
        centerMode: NineSliceCenterMode,
    )

    /**
     * Supplies the exact 200 by 20 highlighted Slider track sprite with a one-pixel tiled horizontal border.
     */
    public fun sliderHighlighted(image: DrawImage)

    /**
     * Supplies the exact 200 by 20 highlighted Slider track sprite and its horizontal sampling policy.
     *
     * @param image immutable track pixels.
     * @param border positive horizontal border width that leaves a nonempty source center.
     * @param centerMode typed center sampling policy.
     * @throws IllegalArgumentException when the slot, image size, or border is invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun sliderHighlighted(
        image: DrawImage,
        border: Int,
        centerMode: NineSliceCenterMode,
    )

    /**
     * Supplies the exact 8 by 20 normal Slider handle sprite.
     */
    public fun sliderHandle(image: DrawImage)

    /**
     * Supplies the exact 8 by 20 highlighted Slider handle sprite.
     */
    public fun sliderHandleHighlighted(image: DrawImage)

    /**
     * Supplies the exact 200 by 20 unfocused TextField sprite.
     *
     * @param image immutable normal text-field pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 200 by 20.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun textFieldNormal(image: DrawImage)

    /**
     * Supplies the exact 200 by 20 focused TextField sprite.
     *
     * @param image immutable highlighted text-field pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 200 by 20.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun textFieldHighlighted(image: DrawImage)

    /**
     * Supplies one exact 8 by 8 transparent-white or opaque-white printable-ASCII glyph mask.
     *
     * @param codePoint one code point from U+0021 through U+007E.
     * @param mask immutable binary-white glyph pixels.
     * @throws IllegalArgumentException when the slot, range, size, or binary pixel values are invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun printableAsciiGlyph(
        codePoint: Int,
        mask: DrawImage,
    )

    /**
     * Supplies the normal 200 by 20 Button sprite.
     *
     * @param image immutable sprite pixels.
     * @param border positive horizontal border width that leaves a nonempty 200-pixel source center.
     * @param centerMode typed center sampling policy.
     * @throws IllegalArgumentException when the slot, image size, or border is invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun buttonNormal(
        image: DrawImage,
        border: Int,
        centerMode: NineSliceCenterMode,
    )

    /**
     * Supplies the highlighted 200 by 20 Button sprite.
     *
     * @param image immutable sprite pixels.
     * @param border positive horizontal border width that leaves a nonempty 200-pixel source center.
     * @param centerMode typed center sampling policy.
     * @throws IllegalArgumentException when the slot, image size, or border is invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun buttonHighlighted(
        image: DrawImage,
        border: Int,
        centerMode: NineSliceCenterMode,
    )

    /**
     * Supplies the disabled 200 by 20 Button sprite.
     *
     * @param image immutable sprite pixels.
     * @param border positive horizontal border width that leaves a nonempty 200-pixel source center.
     * @param centerMode typed center sampling policy.
     * @throws IllegalArgumentException when the slot, image size, or border is invalid.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun buttonDisabled(
        image: DrawImage,
        border: Int,
        centerMode: NineSliceCenterMode,
    )
}
