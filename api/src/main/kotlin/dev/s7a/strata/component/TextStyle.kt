package dev.s7a.strata.component

import dev.s7a.strata.render.ArgbColor
import kotlin.jvm.JvmField

/**
 * Immutable foreground and independent shadow policy shared by labels and editors.
 * Font selection and logical text metrics are independent of this value.
 *
 * @property color foreground tint, including alpha.
 * @property shadow shadow policy; null omits shadow images and drawing commands in both enabled states.
 * @property disabledColor optional foreground override for disabled editors; null uses [color].
 */
public data class TextStyle(
    public val color: ArgbColor = ArgbColor(-1),
    public val shadow: TextShadow? = TextShadow.Automatic,
    public val disabledColor: ArgbColor? = null,
) {
    /**
     * Standard Minecraft-compatible presets, which can also be customized with [copy].
     */
    public companion object {
        /**
         * Opaque white foreground with its ordinary dark shadow.
         */
        @JvmField
        public val Normal: TextStyle = TextStyle()

        /**
         * Muted gray foreground with its ordinary dark shadow.
         */
        @JvmField
        public val Inactive: TextStyle = TextStyle(ArgbColor(0xffa0a0a0.toInt()))

        /**
         * Dark gray foreground without a shadow for labels inside light panels.
         */
        @JvmField
        public val ContainerLabel: TextStyle = TextStyle(ArgbColor(0xff404040.toInt()), shadow = null)

        /**
         * Enabled and disabled text-field foregrounds with their ordinary dark shadows.
         */
        @JvmField
        public val TextField: TextStyle = TextStyle(ArgbColor(0xffe0e0e0.toInt()), disabledColor = ArgbColor(0xff707070.toInt()))
    }
}
