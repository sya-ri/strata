package dev.s7a.strata.integration.minecraft.fabric

/**
 * Inspects real adapter-owned composed outputs without borrowing or retaining their native storage.
 * Instances contain no native resources and remain usable after the independent source fixture closes.
 */
internal fun interface MinecraftCompositionTargetValidation {
    /**
     * Checks current composed [textures] on the client render thread and returns the number of detached intermediate attachments.
     * Adapter-specific reflection and native assertions propagate; caller bindings are restored on every exit.
     */
    fun inspect(textures: List<Any>): Int
}
