package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.render.DrawImage
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
     * Supplies the profile's exact 16 by 16 Minecraft menu texture.
     *
     * @param image immutable menu-background pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 16 by 16.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun menuBackground(image: DrawImage)

    /**
     * Supplies the exact 256 by 256 generic-container texture used by 26.2 chest screens.
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
     * The runtime uses the verified one-pixel 26.2 border and tiled center policy.
     *
     * @param image immutable scrollbar-track pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 6 by 32.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun scrollbarBackground(image: DrawImage)

    /**
     * Supplies the exact 6 by 32 tiled nine-slice scrollbar-thumb sprite.
     *
     * The runtime uses the verified one-pixel 26.2 border and tiled center policy.
     *
     * @param image immutable scrollbar-thumb pixels.
     * @throws IllegalArgumentException when the slot is duplicated or the image size is not 6 by 32.
     * @throws IllegalStateException when called from another thread or after the builder callback ends.
     */
    public fun scrollbarThumb(image: DrawImage)

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
