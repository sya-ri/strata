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
     * Checks the native extent bound required by the shader's exact signed-integer pixel-center arithmetic.
     *
     * This pure check may run on any thread and allocates no native resource.
     * Both source and physical destination axes must be in 1 through 32,768; the device may impose a lower limit.
     * The bound applies only to these native adapters, not to CPU Canvas sources or common logical geometry.
     *
     * @param size source mip-zero or physical destination extent to validate before native allocation or sampling.
     * @throws IllegalArgumentException when either axis lies outside the supported arithmetic range.
     */
    @JvmSynthetic
    internal fun requireSupportedExtent(size: IntSize) {
        require(0 < size.width && size.width <= MAXIMUM_AXIS && 0 < size.height && size.height <= MAXIMUM_AXIS) {
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
     * Builds a fragment shader with a direct integer extent uniform for exact nearest mip-zero sampling.
     *
     * This pure operation may run on any thread, retains no lease or target, and neither compiles a program nor changes texture parameters.
     * The driver must set CanvasTargetExtent to the validated physical target extent before drawing.
     * Native shader compilation and its failures remain the consuming driver's responsibility.
     *
     * @param origin identifies which logical row is stored at source texel row zero.
     * @return immutable GLSL source; no GPU work occurs during construction.
     */
    @JvmSynthetic
    internal fun fragment(origin: MinecraftCanvasTextureOrigin): String = fragment(origin, "uniform ivec3 CanvasTargetExtent;")

    /**
     * Builds the same exact nearest sampler with a 16-byte std140 CanvasCapture uniform block.
     *
     * The block contains the validated physical width and height followed by two unused integers.
     * The driver owns its immutable capture-specific buffer through the capture completion fence; this pure function owns no buffer or cache.
     * Construction is safe on any thread, while compilation and native failures remain the consuming driver's responsibility.
     *
     * @param origin identifies which logical row is stored at source texel row zero.
     * @return immutable GLSL source using the same integer sampling rule as the direct-uniform variant.
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
