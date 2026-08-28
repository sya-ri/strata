package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.locale.Language
import net.minecraft.server.packs.PackType

/**
 * Supplies the compiler-selected Minecraft 1.20.2 font resource and shader contract without runtime version dispatch.
 * The returned value contains no native ownership and may be shared across host snapshots.
 */
@JvmSynthetic
internal fun fabricMinecraftFontCompatibility(): MinecraftFontCompatibility =
    MinecraftFontCompatibility(
        rasterizer = MinecraftTrueTypeRasterizer.Stb,
        packFormat = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
        providerFilters = false,
        packOverlays = true,
        packFormatMinor = 0,
        minorPackFormats = false,
        interleavedShadows = false,
    )

/**
 * Copies current client font and language options on the client thread without retaining the client.
 *
 * @param minecraft active owner client.
 * @return immutable font-selection options pinned by the profile.
 */
@JvmSynthetic
internal fun fabricMinecraftFontOptions(minecraft: Minecraft): MinecraftFontOptions =
    MinecraftFontOptions(
        uniform = minecraft.options.forceUnicodeFont().get(),
        japaneseVariants = false,
        rightToLeft = Language.getInstance().isDefaultRightToLeft(),
    )
