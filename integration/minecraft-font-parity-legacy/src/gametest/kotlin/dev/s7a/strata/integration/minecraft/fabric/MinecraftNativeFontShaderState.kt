package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.system.MemoryStack

/**
 * Reads the actual native text programs' last-applied uniforms and sampler resources after a completed draw.
 * This development-runtime diagnostic runs on the render thread, borrows all programs and textures, and restores texture binding and packing state.
 * Named sampler introspection is omitted from production runs because private names are not a remapped public ABI.
 */
internal object MinecraftNativeFontShaderState {
    /**
     * Returns detached values from GPU uniforms and the actual Sampler2 texture retained by the native shader after its draw.
     * A missing expected program, sampler, or texture aborts the diagnostic instead of substituting presumed fullbright values.
     */
    fun capture(): String {
        RenderSystem.assertOnRenderThread()
        if (FabricLoader.getInstance().isDevelopmentEnvironment.not()) return "Private sampler diagnostics require the named development runtime."
        val samplers = ShaderInstance::class.java.getDeclaredField("samplerMap").apply { isAccessible = true }
        return buildString {
            appendLine("renderSystemShaderColor=${RenderSystem.getShaderColor().joinToString()}")
            listOf(checkNotNull(GameRenderer.getRendertypeTextShader()), checkNotNull(GameRenderer.getRendertypeTextIntensityShader())).forEach { shader ->
                val samplerMap = samplers.get(shader) as Map<*, *>
                val lightmap = checkNotNull(samplerMap["Sampler2"] as? Int) { "Native text shader did not retain its actual integer Sampler2 texture." }
                appendLine("program=${shader.name}; id=${shader.id}; Sampler0=${samplerMap["Sampler0"]}; Sampler2=$lightmap")
                appendLine("ColorModulator=${uniform(shader, "ColorModulator", 4)}")
                appendLine("FogStart=${uniform(shader, "FogStart", 1)}; FogEnd=${uniform(shader, "FogEnd", 1)}; FogColor=${uniform(shader, "FogColor", 4)}")
                appendLine("ModelViewMat=${uniform(shader, "ModelViewMat", 16)}")
                appendLine("ProjMat=${uniform(shader, "ProjMat", 16)}")
                appendLine("Sampler2Unit=${GL20.glGetUniformi(shader.id, GL20.glGetUniformLocation(shader.id, "Sampler2"))}; ${fullbright(lightmap)}")
            }
        }
    }

    private fun uniform(
        shader: ShaderInstance,
        name: String,
        count: Int,
    ): String =
        MemoryStack.stackPush().use { stack ->
            val location = GL20.glGetUniformLocation(shader.id, name)
            check(0 <= location) { "Expected native text uniform is absent: ${shader.name}/$name" }
            val values = stack.mallocFloat(count)
            GL20.glGetUniformfv(shader.id, location, values)
            (0 until count).joinToString { index -> "${values[index]}[${values[index].toRawBits().toUInt().toString(16)}]" }
        }

    private fun fullbright(texture: Int): String {
        check(0 < texture) { "Native text sampler has no lightmap texture." }
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        try {
            RenderSystem.bindTexture(texture)
            val width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            val format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
            check(width == 16 && height == 16) { "Unexpected native text lightmap dimensions: ${width}x$height" }
            return NativeImage(width, height, false).use { image ->
                image.downloadTexture(0, false)
                "lightmapSize=${width}x$height; lightmapFormat=$format; fullbrightABGR=${image.getPixelRGBA(15, 15).toUInt().toString(16)}"
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment)
            RenderSystem.bindTexture(previousTexture)
        }
    }
}
