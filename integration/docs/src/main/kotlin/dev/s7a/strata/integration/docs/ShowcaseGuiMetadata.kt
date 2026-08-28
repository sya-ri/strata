package dev.s7a.strata.integration.docs

import com.google.gson.JsonElement
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits

/**
 * Exact metadata contracts for the official GUI assets used by the detached showcase profile.
 * Validation consumes bounded external JSON without modifying or retaining it.
 */
internal sealed interface ShowcaseGuiMetadata {
    /**
     * Validates the selected image's metadata under the same immutable input ceilings as its font snapshot.
     * Missing, unexpected, or nonconforming metadata fails before a profile is published.
     */
    fun validate(
        bytes: ByteArray?,
        image: IntSize,
        limits: MinecraftFontLoadLimits,
    )

    /**
     * A source image whose official input has no adjacent metadata document.
     */
    data object None : ShowcaseGuiMetadata {
        override fun validate(
            bytes: ByteArray?,
            image: IntSize,
            limits: MinecraftFontLoadLimits,
        ) {
            require(bytes == null) { "Unexpected showcase image metadata." }
        }
    }

    /**
     * Exact GUI nine-slice dimensions, four borders, and center mode of one official sprite.
     */
    data class NineSlice(
        val border: Insets,
        val stretchInner: Boolean = false,
    ) : ShowcaseGuiMetadata {
        override fun validate(
            bytes: ByteArray?,
            image: IntSize,
            limits: MinecraftFontLoadLimits,
        ) {
            val document = ShowcaseAssetJson.document(requireNotNull(bytes) { "Missing showcase nine-slice metadata." }, limits)
            val gui = ShowcaseAssetJson.objectValue(document.get("gui"))
            val scaling = ShowcaseAssetJson.objectValue(gui.get("scaling"))
            val kind = ScalingKind.entries.singleOrNull { candidate -> candidate.externalName == ShowcaseAssetJson.string(scaling.get("type")) }
            require(kind == ScalingKind.NineSlice) { "Showcase GUI metadata must use nine-slice scaling." }
            val size = IntSize(ShowcaseAssetJson.integer(scaling.get("width")), ShowcaseAssetJson.integer(scaling.get("height")))
            require(size == image) { "Showcase GUI metadata has different source dimensions." }
            require(readBorder(scaling.get("border")) == border) { "Showcase GUI metadata has different borders." }
            val stretches = scaling.get("stretch_inner")?.let(ShowcaseAssetJson::boolean) ?: false
            require(stretches == stretchInner) { "Showcase GUI metadata has a different center mode." }
        }

        private fun readBorder(value: JsonElement?): Insets =
            if (value?.isJsonObject == true) {
                val border = value.asJsonObject
                Insets(
                    left = ShowcaseAssetJson.integer(border.get("left")),
                    top = ShowcaseAssetJson.integer(border.get("top")),
                    right = ShowcaseAssetJson.integer(border.get("right")),
                    bottom = ShowcaseAssetJson.integer(border.get("bottom")),
                )
            } else {
                val size = ShowcaseAssetJson.integer(value)
                Insets(size, size, size, size)
            }

        private enum class ScalingKind(
            val externalName: String,
        ) {
            NineSlice("nine_slice"),
        }
    }

    /**
     * Exact frame extent and tick duration for the three-frame loading strip.
     */
    data class Animation(
        val frame: IntSize,
        val ticks: Int,
    ) : ShowcaseGuiMetadata {
        override fun validate(
            bytes: ByteArray?,
            image: IntSize,
            limits: MinecraftFontLoadLimits,
        ) {
            val document = ShowcaseAssetJson.document(requireNotNull(bytes) { "Missing showcase animation metadata." }, limits)
            val animation = ShowcaseAssetJson.objectValue(document.get("animation"))
            val size = IntSize(ShowcaseAssetJson.integer(animation.get("width")), ShowcaseAssetJson.integer(animation.get("height")))
            require(size == frame && image.width == frame.width && image.height == frame.height * 3) { "Showcase loading frames have different dimensions." }
            require(ShowcaseAssetJson.integer(animation.get("frametime")) == ticks) { "Showcase loading frames have a different duration." }
            require(animation.has("frames").not()) { "Showcase loading frames must retain their original order." }
            require(animation.has("interpolate").not()) { "Showcase loading frames must retain their discrete sampling." }
        }
    }
}
