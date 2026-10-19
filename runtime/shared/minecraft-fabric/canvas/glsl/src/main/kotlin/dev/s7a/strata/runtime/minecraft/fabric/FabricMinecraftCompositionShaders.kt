package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Supplies the same shipped integer arithmetic and composition source to every native adapter.
 * Strings are immutable device-independent program descriptions; they retain no images, commands or native state.
 * Arithmetic handles positive zero and nonnegative normal finite binary32 intermediates with round-to-nearest-even.
 * [GLSL 1.50](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.50.pdf) defines the 32-bit integer operations;
 * the shader documents every product bound and guards zero and large shift distances.
 */
internal object FabricMinecraftCompositionShaders {
    /**
     * Fullscreen triangle whose first vertex selects one immutable three-row command metadata page.
     */
    @get:JvmSynthetic
    internal val vertex: String =
        """
        #version 150
        noperspective out vec2 canvasUv;
        flat out int compositionCommand;
        void main() {
            int vertex = gl_VertexID % 3;
            vec2 uv = vec2((vertex << 1) & 2, vertex & 2);
            gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            canvasUv = uv;
            compositionCommand = gl_VertexID / 3;
        }
        """.trimIndent()

    /**
     * Exact sampled/integer source-over with no native blend, floating color arithmetic or intermediate byte reuse.
     * Missing packaged shader source fails explicitly before native compilation.
     */
    @get:JvmSynthetic
    internal val fragment: String =
        """
        #version 150
        uniform sampler2D InSampler;
        uniform sampler2D DestinationSampler;
        uniform sampler2D IndexSampler;
        uniform sampler2D FactorSampler;
        noperspective in vec2 canvasUv;
        flat in int compositionCommand;
        out vec4 fragColor;
        """.trimIndent() + "\n" + resource("strata_float32.glsl") + "\n" + resource("strata_composition.glsl")

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/assets/strata/shaders/include/$name")) { "Packaged composition shader is missing: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
