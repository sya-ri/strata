package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Decodes bounded CPU-selected texel indices and draws immutable source pixels entirely on the GPU.
 * No normalized source interpolation, floating-point source arithmetic, readback, or persistent shader state is used.
 * Compiled programs belong to the host device's existing pipeline cache.
 */
internal object FabricMinecraftSamplingShaders {
    /**
     * Fragment source shared by device adapters; the index sampler stores little-endian RGBA integer metadata.
     */
    @get:JvmSynthetic
    internal val fragment: String =
        """
        #version 150
        uniform sampler2D InSampler;
        uniform sampler2D IndexSampler;
        noperspective in vec2 canvasUv;
        out vec4 fragColor;
        int indexAt(ivec2 position) {
            ivec4 bytes = ivec4(floor(texelFetch(IndexSampler, position, 0) * 255.0 + 0.5));
            return bytes.x | (bytes.y << 8) | (bytes.z << 16) | (bytes.w << 24);
        }
        void main() {
            ivec2 targetExtent = ivec2(indexAt(ivec2(0, 2)), indexAt(ivec2(1, 2))) - 1;
            ivec2 pixel = clamp(ivec2(floor(canvasUv * vec2(targetExtent))), ivec2(0), targetExtent - 1);
            ivec2 source = ivec2(indexAt(ivec2(pixel.x, 0)), indexAt(ivec2(pixel.y, 1))) - 1;
            if (any(lessThan(source, ivec2(0)))) {
                fragColor = vec4(0.0);
            } else {
                vec4 color = texelFetch(InSampler, source, 0);
                int effects = indexAt(ivec2(2, 2));
                int alpha = int(floor(color.a * 255.0 + 0.5));
                if (alpha < (effects >> 3)) {
                    fragColor = vec4(0.0);
                } else {
                    color.rgb *= vec3(float(effects & 1), float((effects >> 1) & 1), float((effects >> 2) & 1));
                    fragColor = color;
                }
            }
        }
        """.trimIndent()
}
