package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Supplies immutable shader source for exact nearest sampling without changing externally owned texture parameters.
 *
 * GPU abstraction programs belong to Minecraft's per-device pipeline cache; the legacy driver owns its two fixed programs until device shutdown.
 * This object owns no native objects, source leases, target generations, or mutable cache.
 */
internal object FabricNativeCanvasShaders {
    private const val MAXIMUM_AXIS = 32_768

    /**
     * Validates native source or target axes in 1 through 32,768 for exact signed-integer sampling.
     * The device may impose a lower limit; CPU sources and common logical geometry use their own bounds.
     *
     * @throws IllegalArgumentException when either axis is outside that range.
     */
    @JvmSynthetic
    internal fun requireSupportedExtent(size: IntSize) {
        require(size.width in 1..MAXIMUM_AXIS && size.height in 1..MAXIMUM_AXIS) {
            "Native Canvas image axes must be between 1 and $MAXIMUM_AXIS pixels; the device may impose a lower limit."
        }
    }

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
     * Builds exact nearest mip-zero GLSL using a direct CanvasTargetExtent uniform.
     * The driver sets the validated physical target extent and owns compilation and its failures.
     *
     * @param origin logical edge stored at source texel row zero.
     */
    @JvmSynthetic
    internal fun fragment(origin: MinecraftCanvasTextureOrigin): String = fragment(origin, "uniform ivec3 CanvasTargetExtent;")

    /**
     * Builds the nearest sampler with a 16-byte std140 CanvasCapture block: physical width, height, then two unused integers.
     * The driver owns compilation and keeps the capture-specific buffer alive through its completion fence.
     *
     * @param origin logical edge stored at source texel row zero.
     */
    @JvmSynthetic
    internal fun bufferedFragment(origin: MinecraftCanvasTextureOrigin): String = fragment(origin, "layout(std140) uniform CanvasCapture { ivec3 CanvasTargetExtent; };")

    private fun fragment(
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
