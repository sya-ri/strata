package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryStack
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.function.IntSupplier

/**
 * Checks real legacy framebuffer container references after successful preparation and partial native initialization.
 * Live output owners must expose detached scratch attachments while their independent final attachments remain valid.
 * Deleted texture names are never treated as proof that their backing storage was freed.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCompositionGlRetentionValidation : MinecraftCanvasInputValidation, MinecraftCompositionTargetValidation {
    override fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        repeat(8) { attempt -> context.onClient { rejectSource(1 + attempt % 2) } }
        Files.writeString(
            context.outputDirectory.resolve("composition-gl-partial-retention.properties"),
            "actualPartialInitializations=8\noddAndEvenSlots=true\nborrowedAttachments=none\nprimaryIdentity=true\ncallerStateRestored=true\nresult=passed\n",
        )
    }

    override fun inspect(textures: List<Any>): Int =
        textures.sumOf { texture ->
            val storage = MinecraftCompositionParityInputs.member(texture, "storage")
            val detached = requireDetached(storage)
            val output = MinecraftCompositionParityInputs.member(storage, "output") as Int
            check(0 <= output)
            requireOwnedAttachment(storage, output)
            detached
        }

    // Distinct caller read/draw containers expose restoration mistakes in the shared-attachment cleanup.
    @Suppress("TooGenericExceptionCaught")
    private fun rejectSource(passCount: Int) {
        val draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val caller = IntArray(2)
        var failure: Throwable? = null
        try {
            caller.indices.forEach { index ->
                caller[index] = GL30.glGenFramebuffers()
                check(caller[index] != 0)
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, caller[0])
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, caller[1])
            rejectSourceInBorrowedState(passCount)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw) },
                { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read) },
                { GL30.glDeleteFramebuffers(caller[0]) },
                { GL30.glDeleteFramebuffers(caller[1]) },
            )
        }
    }

    // A source-name failure must remain primary while both real framebuffer attachments already exist.
    @Suppress("TooGenericExceptionCaught")
    private fun rejectSourceInBorrowedState(passCount: Int) {
        val type = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftGlCompositionStorage")
        val constructor = type.getDeclaredConstructor().also { check(it.trySetAccessible()) }
        val storage = constructor.newInstance() as NativeGuiResource
        val scratch = createScratch()
        val primary = IllegalStateException("Injected source borrow after native framebuffer attachment.")
        var failure: Throwable? = null
        try {
            val before = bindings()
            var sourceCalls = 0
            val source = borrowedTexture {
                sourceCalls++
                val index = MinecraftCompositionParityInputs.member(storage, "borrowedOutput") as Int
                val framebuffers = MinecraftCompositionParityInputs.member(storage, "framebuffers") as IntArray
                check(framebuffers.size == 2 && framebuffers.all { it != 0 && GL30.glIsFramebuffer(it) })
                check(attachment(framebuffers[index], GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == scratch)
                throw primary
            }
            NativeImage(1, 1, true).use { indices ->
                NativeImage(1, 1, true).use { factors ->
                    val initialize = type.declaredMethods.single { it.name.startsWith("initialize") && it.parameterCount == 5 }
                    check(initialize.trySetAccessible())
                    val rejected = runCatching { initialize.invoke(storage, indices, factors, IntSize(16, 16), List(passCount) { source }, borrowedTexture { scratch }) }.exceptionOrNull()
                    check(rejected is InvocationTargetException && rejected.targetException === primary)
                }
            }
            check(sourceCalls == 1)
            check(bindings() == before) { "Partial composition initialization changed caller OpenGL state." }
            check(requireDetached(storage) == 1)
            val borrowed = MinecraftCompositionParityInputs.member(storage, "borrowedOutput") as Int
            requireOwnedAttachment(storage, 1 - borrowed)
            check(GL11.glGetError() == GL11.GL_NO_ERROR)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { GL11.glFinish() },
                { GL11.glDeleteTextures(scratch) },
                { if (failure == null) check(requireDetached(storage) == 1) },
                { storage.close() },
                { check(storage.isDestroyed()) },
            )
        }
    }

    private fun borrowedTexture(name: () -> Int): AbstractTexture {
        val type = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricPortableBorrowedTexture")
        val constructor = type.getDeclaredConstructor(IntSupplier::class.java).also { check(it.trySetAccessible()) }
        return constructor.newInstance(IntSupplier { name() }) as AbstractTexture
    }

    private fun requireDetached(storage: Any): Int {
        val borrowed = MinecraftCompositionParityInputs.member(storage, "borrowedOutput") as Int
        if (borrowed < 0) return 0
        val framebuffers = MinecraftCompositionParityInputs.member(storage, "framebuffers") as IntArray
        check(framebuffers[borrowed] != 0 && GL30.glIsFramebuffer(framebuffers[borrowed]))
        check(attachment(framebuffers[borrowed], GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL11.GL_NONE) {
            "A retained final owner still references generation-local scratch through its framebuffer."
        }
        return 1
    }

    private fun requireOwnedAttachment(
        storage: Any,
        output: Int,
    ) {
        val framebuffers = MinecraftCompositionParityInputs.member(storage, "framebuffers") as IntArray
        val textures = MinecraftCompositionParityInputs.member(storage, "textures") as IntArray
        check(framebuffers[output] != 0 && GL30.glIsFramebuffer(framebuffers[output]))
        check(attachment(framebuffers[output], GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL11.GL_TEXTURE)
        check(attachment(framebuffers[output], GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) == textures[output])
    }

    private fun attachment(
        framebuffer: Int,
        parameter: Int,
    ): Int {
        val previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        return try {
            GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, parameter)
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous)
        }
    }

    // The fixture owns the texture only after complete allocation; all failures attempt independent deletion and binding restoration.
    @Suppress("TooGenericExceptionCaught")
    private fun createScratch(): Int {
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val texture = GL11.glGenTextures()
        try {
            check(texture != 0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            MinecraftCanvasGlPixelStorage().use {
                MemoryStack.stackPush().use { stack ->
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, stack.calloc(16 * 16 * 4))
                }
            }
            return texture
        } catch (failure: Throwable) {
            runCanvasTestCleanup(failure, { GL11.glDeleteTextures(texture) })
            throw failure
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
        }
    }

    private fun bindings(): List<Any> {
        val active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        val samplers = GL.getCapabilities().let { it.OpenGL33 || it.GL_ARB_sampler_objects }
        val units =
            try {
                List(4) { unit ->
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
                    GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) to if (samplers) GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) else 0
                }
            } finally {
                GL13.glActiveTexture(active)
            }
        return listOf(
            active,
            units,
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }.toList(),
            IntArray(4).also { GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, it) }.toList(),
            IntArray(4).also { GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, it) }.toList(),
            FloatArray(4).also { GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, it) }.toList(),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
            GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE),
            listOf(GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST, GL11.GL_DITHER).map(GL11::glIsEnabled),
            listOf(GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS).map(GL11::glGetInteger),
        )
    }
}
