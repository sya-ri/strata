@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanDevice
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan.FabricVulkanCanvasDeviceAccessor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.GuiRenderer
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Draws the unchanged complete native scene through the standard GUI renderer into an owned RGBA32F target.
 * The test-only widened target field is restored in finally, together with the borrowed projection and captured GL state.
 * The render thread owns this capture session, its bounded pipeline declarations, temporary targets, render state, native buffers, and readback resources.
 * Every scale first requires exact visible RGB against the opaque ordinary native screenshot before accepting a format-only RGBA32F capture.
 * Calibration and float captures preserve their raw alpha; Minecraft's ordinary screenshot normalizes alpha and cannot validate that hidden channel.
 */
internal class MinecraftNativeFontFloatTarget : AutoCloseable {
    private val pipelines = MinecraftNativeFontCaptureState.Pipelines()

    /**
     * Writes big-endian width and height followed by top-down RGBA float32 pixels, plus measured per-scale precision metadata.
     * No native pixel or measured value becomes an input to the portable font renderer.
     */
    fun captureScene(
        output: Path,
        scale: Int,
        draw: (GuiGraphicsExtractor) -> Unit = {
            MinecraftNativeFontScene.background(it)
            MinecraftNativeFontScene.text(it)
        },
        atlasSize: () -> IntSize = { MinecraftNativeFontVertices.capture(output) },
    ) {
        RenderSystem.assertOnRenderThread()
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val frame = minecraft.gameRenderer.gameRenderState().windowRenderState
        check(window.guiScale == scale && frame.guiScale == scale) { "Native float GUI density differs from the requested scene." }
        check(frame.width == window.width && frame.height == window.height) { "Native float capture frame has a stale physical viewport." }
        check(frame.width == MinecraftFontParityFixture.viewport.width * scale && frame.height == MinecraftFontParityFixture.viewport.height * scale) { "Native float capture viewport differs from the fixture." }
        val backend = MinecraftCanvasTestBackend.parse(RenderSystem.getDevice().deviceInfo.backendName())
        val capture = {
            val atlas = atlasSize()
            val size = IntSize(frame.width, frame.height)
            val calibration = output.resolve("font-native-calibration-$scale.png")
            captureTarget(calibration, size, minecraft, GpuFormat.RGBA8_UNORM, draw)
            MinecraftFontGpuReceipt.verifyVisibleCalibration(output.resolve("font-native-$scale.png"), calibration, scale)
            captureTarget(output.resolve("font-native-$scale.rgba32f"), size, minecraft, GpuFormat.RGBA32_FLOAT, draw)
            Files.writeString(
                output.resolve("native-float-state-$scale.properties"),
                "scale=$scale\nwidth=${frame.width}\nheight=${frame.height}\nsubpixelBits=${subpixelBits(backend)}\nmaxAtlasWidth=${atlas.width}\nmaxAtlasHeight=${atlas.height}\ncolorFormat=RGBA32F\npreparationThreads=1\npipelineFormatOnly=true\nvisibleRgbCalibration=exact\n",
            )
        }
        when (backend) {
            MinecraftCanvasTestBackend.OpenGl -> MinecraftNativeFontGlState.capture().use { capture() }
            MinecraftCanvasTestBackend.Vulkan -> capture()
        }
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        pipelines.close()
    }

    private fun captureTarget(
        output: Path,
        size: IntSize,
        minecraft: Minecraft,
        format: GpuFormat,
        draw: (GuiGraphicsExtractor) -> Unit,
    ) {
        val renderer = minecraft.gameRenderer
        val original = renderer.mainRenderTarget()
        val projection = checkNotNull(RenderSystem.getProjectionMatrixBuffer()) { "Native scene capture requires the active game projection." }
        val projectionType = RenderSystem.getProjectionType()
        val target = TextureTarget("Strata native font capture oracle", size.width, size.height, original.useDepth, format)
        try {
            val state = MinecraftNativeFontCaptureState(pipelines, format)
            GuiRenderer(state, renderer.featureRenderDispatcher(), emptyList()).use { gui ->
                try {
                    renderer.mainRenderTarget = target
                    clearTarget(target)
                    val graphics = GuiGraphicsExtractor(minecraft, state, 0, 0)
                    draw(graphics)
                    gui.render()
                    writePixels(output, target)
                } finally {
                    renderer.mainRenderTarget = original
                    RenderSystem.setProjectionMatrix(projection, projectionType)
                }
            }
        } finally {
            target.destroyBuffers()
        }
    }

    private fun clearTarget(target: RenderTarget) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val color = checkNotNull(target.colorTexture)
        val depth = target.depthTexture
        if (depth == null) encoder.clearColorTexture(color, Vector4f()) else encoder.clearColorAndDepthTextures(color, Vector4f(), depth, 0.0)
        encoder.submit()
    }

    private fun subpixelBits(backend: MinecraftCanvasTestBackend): Int =
        when (backend) {
            MinecraftCanvasTestBackend.OpenGl -> {
                GL11.glGetInteger(GL11.GL_SUBPIXEL_BITS)
            }

            MinecraftCanvasTestBackend.Vulkan -> {
                val device = (RenderSystem.getDevice() as FabricVulkanCanvasDeviceAccessor).strataCanvasBackend()
                check(device is VulkanDevice) { "The selected Vulkan backend does not expose the active Vulkan device." }
                MemoryStack.stackPush().use { stack ->
                    val properties = VkPhysicalDeviceProperties.malloc(stack)
                    VK10.vkGetPhysicalDeviceProperties(device.vkDevice().physicalDevice, properties)
                    properties.limits().subPixelPrecisionBits()
                }
            }
        }

    private fun writePixels(
        output: Path,
        target: RenderTarget,
    ) {
        val texture = checkNotNull(target.colorTexture)
        val size = IntSize(target.width, target.height)
        val device = RenderSystem.getDevice()
        device.createBuffer({ "Strata native scene capture readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, size.width.toLong() * size.height * texture.format.blockSize()).use { buffer ->
            val encoder = device.createCommandEncoder()
            encoder.copyTextureToBuffer(texture, buffer, 0L, {}, 0)
            encoder.createFence().use { fence ->
                encoder.submit()
                check(fence.awaitCompletion(5_000_000_000L)) { "Native scene float readback did not complete before its bounded GPU timeout." }
            }
            buffer.map(true, false).use { mapped ->
                val bytes = mapped.data().order(ByteOrder.nativeOrder())
                when (texture.format) {
                    GpuFormat.RGBA32_FLOAT -> writeMappedPixels(output, size, bytes)
                    GpuFormat.RGBA8_UNORM -> writeBytePixels(output, size, bytes)
                    else -> error("Native capture readback has an unsupported format: ${texture.format}")
                }
            }
        }
    }

    private fun writeBytePixels(
        output: Path,
        size: IntSize,
        bytes: ByteBuffer,
    ) {
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                val offset = ((size.height - y - 1) * size.width + x) * 4
                val red = bytes.get(offset).toInt() and 0xFF
                val green = bytes.get(offset + 1).toInt() and 0xFF
                val blue = bytes.get(offset + 2).toInt() and 0xFF
                val alpha = bytes.get(offset + 3).toInt() and 0xFF
                image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        check(ImageIO.write(image, "png", output.toFile())) { "Could not write the native calibration screenshot." }
    }

    private fun writeMappedPixels(
        output: Path,
        size: IntSize,
        bytes: ByteBuffer,
    ) {
        DataOutputStream(Files.newOutputStream(output).buffered()).use { stream ->
            stream.writeInt(size.width)
            stream.writeInt(size.height)
            for (y in 0 until size.height) writeFloatRow(stream, bytes, (size.height - y - 1) * size.width * 16, size.width)
        }
    }

    private fun writeFloatRow(
        stream: DataOutputStream,
        bytes: ByteBuffer,
        rowStart: Int,
        width: Int,
    ) {
        for (x in 0 until width) {
            val index = rowStart + x * 16
            repeat(4) { component -> stream.writeFloat(bytes.getFloat(index + component * 4)) }
        }
    }
}
