package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

/**
 * Owns one fixed nearest-sampling shader and empty vertex array for the legacy render device.
 *
 * All operations run on its OpenGL render thread; the driver keeps at most one program per image orientation.
 * Physical deletion occurs only after terminal device completion, never while a Canvas capture is pending.
 */
@Suppress("TooGenericExceptionCaught")
internal class FabricNativeCanvasGlProgram private constructor(
    private val program: Int,
    private val vertexArray: Int,
) : AutoCloseable {
    private var programClosed: Boolean = false
    private var vertexArrayClosed: Boolean = false

    /**
     * Draws the complete target using the source texture and framebuffer already bound by the render-thread driver.
     *
     * The program borrows those bindings only during this call and retains no source, lease, or target generation.
     * The caller must restore changed program and vertex-array state and fence the issued capture work, including after a native failure.
     *
     * @param size validated physical target extent used by exact integer pixel-center sampling.
     * @throws Throwable when native drawing fails; ownership of this program remains with the driver.
     */
    @JvmSynthetic
    internal fun draw(size: IntSize) {
        GL20.glUseProgram(program)
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "InSampler"), 0)
        GL20.glUniform3i(GL20.glGetUniformLocation(program, "CanvasTargetExtent"), size.width, size.height, 0)
        GL30.glBindVertexArray(vertexArray)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
    }

    @JvmSynthetic
    override fun close() {
        var failure: Throwable? = null
        try {
            if (programClosed.not()) {
                GL20.glDeleteProgram(program)
                programClosed = true
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            if (vertexArrayClosed.not()) {
                GL30.glDeleteVertexArrays(vertexArray)
                vertexArrayClosed = true
            }
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        failure?.let { throw it }
    }

    /**
     * Compiles fixed orientation variants on the owning OpenGL render thread without capturing source or target state.
     *
     * The returned program transfers native ownership to the driver; this factory retains no cache or mutable rendering state.
     * Partial compilation and linking failures release the native objects acquired by that construction attempt.
     */
    internal companion object {
        /**
         * Compiles and links one orientation variant, releasing partial objects on failure.
         *
         * @param origin image orientation normalized by the fragment stage.
         * @return a newly owned shader and vertex array.
         * @throws IllegalStateException when the OpenGL compiler or linker rejects the fixed shaders.
         */
        @JvmSynthetic
        internal fun create(origin: MinecraftCanvasTextureOrigin): FabricNativeCanvasGlProgram {
            val vertex = compile(GL20.GL_VERTEX_SHADER, FabricNativeCanvasShaders.vertex)
            val fragment =
                try {
                    compile(GL20.GL_FRAGMENT_SHADER, FabricNativeCanvasShaders.fragment(origin))
                } catch (failure: Throwable) {
                    GL20.glDeleteShader(vertex)
                    throw failure
                }
            val program = GL20.glCreateProgram()
            try {
                GL20.glAttachShader(program, vertex)
                GL20.glAttachShader(program, fragment)
                GL20.glLinkProgram(program)
                check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
                    "Canvas shader linking failed: ${GL20.glGetProgramInfoLog(program)}"
                }
                return FabricNativeCanvasGlProgram(program, GL30.glGenVertexArrays())
            } catch (failure: Throwable) {
                GL20.glDeleteProgram(program)
                throw failure
            } finally {
                GL20.glDeleteShader(vertex)
                GL20.glDeleteShader(fragment)
            }
        }

        private fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GL20.glCreateShader(type)
            try {
                GL20.glShaderSource(shader, source)
                GL20.glCompileShader(shader)
                check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
                    "Canvas shader compilation failed: ${GL20.glGetShaderInfoLog(shader)}"
                }
                return shader
            } catch (failure: Throwable) {
                GL20.glDeleteShader(shader)
                throw failure
            }
        }
    }
}
