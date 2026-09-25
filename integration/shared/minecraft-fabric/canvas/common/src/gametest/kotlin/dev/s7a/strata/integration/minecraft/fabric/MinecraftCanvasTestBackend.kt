package dev.s7a.strata.integration.minecraft.fabric

/**
 * Records the actual loaded GPU backend independently of a requested launch preference.
 *
 * Decoding is confined to native metadata and the optional test-property boundary; unsupported names fail explicitly.
 */
internal enum class MinecraftCanvasTestBackend {
    /**
     * Loaded OpenGL rendering, including supported releases before the Vulkan backend.
     */
    OpenGl,

    /**
     * Loaded Vulkan rendering; an OpenGL fallback does not satisfy this backend.
     */
    Vulkan,

    ;

    /**
     * Decodes external backend metadata without introducing string discrimination into native rendering.
     */
    internal companion object {
        /**
         * Converts an external backend name to a checked evidence value.
         *
         * @throws IllegalArgumentException when the name does not describe a supported test backend.
         */
        internal fun parse(value: String): MinecraftCanvasTestBackend =
            requireNotNull(entries.singleOrNull { it.name.equals(value, ignoreCase = true) }) {
                "Unsupported native Canvas test backend: $value"
            }
    }
}
