package dev.s7a.strata.runtime.minecraft

/**
 * Closed profile-backed color and shadow layers for printable Minecraft text.
 */
public enum class MinecraftTextStyle {
    /**
     * Opaque white foreground with the native dark shadow.
     */
    Normal,

    /**
     * Opaque gray foreground and shadow used by inactive labels.
     */
    Inactive,
}
