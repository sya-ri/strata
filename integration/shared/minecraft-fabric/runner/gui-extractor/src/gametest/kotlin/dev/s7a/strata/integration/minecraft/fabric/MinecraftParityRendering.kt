package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Client-thread headless rendering and borrowed native-image assertions for screenshot parity.
 */
internal object MinecraftParityRendering {
    /**
     * Renders a detached common host on the client thread, including an opaque black framebuffer clear and pointer input.
     * The default pointer matches the native baseline screen captures; component scenes supply their own position.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    internal fun renderHeadless(
        profile: MinecraftUiProfile,
        definition: UiDefinition,
        viewport: IntSize,
        pointerPosition: IntOffset = IntOffset(100, 110),
        frameTime: FrameTime? = null,
        scale: Int = 1,
    ): HeadlessImage =
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            if (frameTime == null) host.frame(viewport) else host.frame(viewport, FrameTime(0L))
            host.dispatchPointer(PointerEvent.Move(pointerPosition))
            val frame = if (frameTime == null) host.frame(viewport) else host.frame(viewport, frameTime)
            val framebufferClear =
                DrawCommand.FillRectangle(
                    IntRect(0, 0, frame.size.width, frame.size.height),
                    ArgbColor(0xFF000000.toInt()),
                )
            rasterizeHeadless(listOf(framebufferClear) + frame.drawCommands, frame.size, scale)
        }

    /**
     * Reports exact size and ARGB equality without consuming or closing the borrowed native image.
     */
    internal fun hasExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ): Boolean {
        val expected = IntSize(native.width, native.height)
        if (headless.size != expected) return false
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (headless.argbAt(x, y) != native.getPixel(x, y)) return false
            }
        }
        return true
    }

    /**
     * Requires the borrowed native image to have the selected physical capture dimensions.
     */
    internal fun requireImageSize(
        image: NativeImage,
        expected: IntSize,
    ) {
        if (image.width == expected.width && image.height == expected.height) return
        throw AssertionError("Native screenshot size was ${image.width}x${image.height}, expected ${expected.width}x${expected.height}.")
    }

    /**
     * Requires exact physical size and ARGB equality, reporting the first differing pixel.
     */
    internal fun requireExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ) {
        val expected = IntSize(native.width, native.height)
        if ((headless.size == expected).not()) {
            throw AssertionError("Headless size was ${headless.size}, expected $expected.")
        }
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expected = native.getPixel(x, y)
                val actual = headless.argbAt(x, y)
                if (actual == expected) continue
                throw AssertionError(
                    "Headless pixel at ($x,$y) was 0x${actual.toUInt().toString(16)}, expected native 0x${expected.toUInt().toString(16)}.",
                )
            }
        }
    }
}
