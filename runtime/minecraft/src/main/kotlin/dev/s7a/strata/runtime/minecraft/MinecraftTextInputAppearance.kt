package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Detached editor frames and colors, owned only by the current description and retained editor.
 * No native handle, subscription, or previous appearance is retained.
 *
 * @property normal unfocused frame.
 * @property focused focused frame.
 * @property disabled inactive frame.
 * @property border source nine-slice insets with a positive center.
 * @property caretColor insertion caret color.
 * @property compositionUnderlineColor focused composition decoration color.
 * @property legacyAppendCaret whether the default field's underscore glyph must be preserved exactly.
 */
internal data class MinecraftTextInputAppearance(
    val normal: DrawImage,
    val focused: DrawImage,
    val disabled: DrawImage = normal,
    val border: Insets = Insets.all(1),
    val caretColor: ArgbColor = ArgbColor(-1),
    val compositionUnderlineColor: ArgbColor = caretColor,
    val legacyAppendCaret: Boolean = true,
) {
    init {
        if (legacyAppendCaret.not()) {
            listOf(normal, focused, disabled).forEach { image ->
                require(border.left.toLong() + border.right < image.size.width && border.top.toLong() + border.bottom < image.size.height) {
                    "Text input appearance borders must leave a nonempty image center."
                }
            }
        }
    }

    /**
     * Paints the selected frame without changing geometry or borrowing a scope beyond this call.
     */
    fun paint(
        scope: PaintScope,
        enabled: Boolean,
        hasFocus: Boolean,
    ) {
        val image =
            when {
                enabled.not() -> disabled
                hasFocus -> focused
                else -> normal
            }
        paintMinecraftNineSlice(scope, image, border, if (legacyAppendCaret) NineSliceCenterMode.Tiled else NineSliceCenterMode.Stretched)
    }
}
