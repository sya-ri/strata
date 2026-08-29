package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommandRenderer
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommands
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasPresentation
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasRequest
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasToken
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Screen-owned detached capture receipt and final native-canvas preparation boundary.
 *
 * The screen confines calls to its client/render thread and releases receipts on removal or terminal failure.
 * Actual GPU lifetimes belong to the independent device registry, never this object.
 * Extra host frames for hover convergence finish before this boundary runs.
 */
@OptIn(InternalStrataRuntimeApi::class)
// Native submission failures must remain primary while independent unqueued cleanup is attempted.
@Suppress("TooGenericExceptionCaught")
internal class FabricMinecraftCanvasPresentation {
    private var receipt: NativeCanvasPresentation? = null
    private var portable: List<DrawCommand>? = null
    private var releaseGeneration: Long = 0L

    /**
     * Prepares and validates one mixed frame, then borrows native handlers only for its ordered GUI submission.
     *
     * @param commands final core command snapshot after layout and hover convergence.
     * @param time timestamp shared by this actual presentation.
     * @param scale positive resolved GUI scale, used for physical target allocation only.
     * @param platformRenderer screen-owned existing platform handler, such as synchronized inventory rendering.
     * @param drawCanvas version-owned target blitter borrowed only during submission.
     * @param submit ordered portable/native presenter borrowed only during this call.
     * @throws Throwable when validation, capture, or submission fails; queued resources remain protected through GUI consumption.
     */
    @JvmSynthetic
    fun <T> present(
        commands: List<DrawCommand>,
        time: FrameTime,
        scale: Number,
        platformRenderer: MinecraftPlatformCommandRenderer<T>,
        drawCanvas: (T, NativeCanvasTarget, IntRect) -> Unit,
        submit: (List<DrawCommand>, MinecraftPlatformCommands<T>) -> Unit,
    ) {
        val generation = releaseGeneration
        val hasCanvas = commands.any { (it as? DrawCommand.Platform)?.command is NativeCanvasRequest }
        if (hasCanvas.not()) {
            val dispatch = MinecraftPlatformCommands(listOf(platformRenderer))
            dispatch.validate(commands)
            submit(commands, dispatch)
            if (releaseGeneration == generation) {
                receipt = null
                portable = commands
            }
            return
        }
        val device = NativeCanvasDevices.device(FabricNativeCanvasDriver)
        val integerScale = scale.toInt()
        require(integerScale.toDouble() == scale.toDouble()) { "Native Canvas GUI scale must be an exact integer." }
        val prepared = device.prepare(commands, time, integerScale)
        var queued = false
        try {
            val canvasRenderer = CanvasRenderer(device, prepared, drawCanvas)
            val dispatch = MinecraftPlatformCommands(listOf(platformRenderer, canvasRenderer))
            dispatch.validate(prepared.drawCommands)
            device.queue(prepared)
            queued = true
            submit(prepared.drawCommands, dispatch)
            if (releaseGeneration == generation) {
                receipt = prepared
                portable = null
            }
        } catch (failure: Throwable) {
            if (queued.not()) {
                runCatching { device.cancel(prepared) }.exceptionOrNull()?.let {
                    FabricMinecraftFailures.addSuppressed(failure, it)
                }
            }
            throw failure
        }
    }

    /**
     * Captures the last successfully submitted frame without resolving live GPU state or reading pixels back.
     *
     * @return immutable portable commands based only on exact same-generation snapshots.
     * @throws IllegalStateException before any output when no frame exists or a native command has no matching snapshot.
     */
    @JvmSynthetic
    fun capture(): List<DrawCommand> {
        receipt?.let { return it.capture() }
        val commands = checkNotNull(portable) { "No complete Minecraft canvas presentation has been submitted." }
        check(commands.none { it is DrawCommand.Platform }) { "Portable capture requires a snapshot for every native platform command." }
        return commands
    }

    /**
     * Clears detached CPU receipts without touching in-flight GPU work or the independent device owner.
     *
     * The screen calls this on its render owner thread during detach or terminal cleanup; it cannot fail.
     */
    @JvmSynthetic
    fun release() {
        releaseGeneration += 1L
        receipt = null
        portable = null
    }

    private class CanvasRenderer<T>(
        private val device: NativeCanvasDevice,
        private val presentation: NativeCanvasPresentation,
        private val draw: (T, NativeCanvasTarget, IntRect) -> Unit,
    ) : MinecraftPlatformCommandRenderer<T> {
        override fun accepts(command: PlatformDrawCommand): Boolean = command is NativeCanvasToken

        override fun render(
            target: T,
            command: DrawCommand.Platform,
        ) {
            val token = checkNotNull(command.command as? NativeCanvasToken)
            draw(target, device.target(presentation, token), command.bounds)
        }
    }
}
