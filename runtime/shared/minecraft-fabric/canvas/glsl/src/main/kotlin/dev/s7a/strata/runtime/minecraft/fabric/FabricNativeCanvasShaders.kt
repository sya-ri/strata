package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Supplies immutable shader source for exact nearest sampling without changing externally owned texture parameters.
 *
 * GPU abstraction programs belong to Minecraft's per-device pipeline cache; the legacy driver owns its two fixed programs until device shutdown.
 * This object owns no native objects, source leases, target generations, or mutable cache.
 */
internal object FabricNativeCanvasShaders {
    /**
     * Immutable vertex-shader source for a fullscreen triangle with normalized image coordinates.
     *
     * Reading the source is safe from any thread and creates no native program or resource.
     * The consuming driver owns compilation failures and each compiled program's device lifetime.
     */
    @get:JvmSynthetic
    internal val vertex: String =
        """
        #version 150
        noperspective out vec2 canvasUv;
        void main() {
            vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            canvasUv = uv;
        }
        """.trimIndent()

    /**
     * Builds exact nearest mip-zero GLSL with the native driver's target extent binding.
     * The driver owns compilation failures and the matching uniform or capture-buffer lifetime.
     *
     * @param origin logical edge stored at source texel row zero.
     * @param targetDeclaration GLSL declaration exposing the driver's `ivec3 CanvasTargetExtent` value.
     */
    @JvmSynthetic
    internal fun fragment(
        origin: MinecraftCanvasTextureOrigin,
        targetDeclaration: String,
    ): String {
        val sourceY = if (origin == MinecraftCanvasTextureOrigin.TopLeft) "pixel.y" else "extent.y - pixel.y - 1"
        return """
            #version 150
            uniform sampler2D InSampler;
            $targetDeclaration
            noperspective in vec2 canvasUv;
            out vec4 fragColor;
            void main() {
                ivec2 extent = textureSize(InSampler, 0);
                ivec2 targetExtent = CanvasTargetExtent.xy;
                ivec2 destinationPixel = clamp(ivec2(floor(canvasUv * vec2(targetExtent))), ivec2(0), targetExtent - 1);
                ivec2 pixel = ((destinationPixel * 2 + 1) * extent) / (targetExtent * 2);
                fragColor = texelFetch(InSampler, ivec2(pixel.x, $sourceY), 0);
            }
            """.trimIndent()
    }
}
