package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasCapture
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasPresentation
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasProducer
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasSnapshot
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasToken
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Owns a bounded CPU protocol fixture and detached completed publication lists on its construction thread.
 * The normal JVM constructor handles avoid adding a benchmark bridge or bypassing runtime archive provenance.
 * No callback issues GPU work; native measurements must report that separate scope independently.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasPresentationWorkload(
    private val canvasCount: Int,
    private val portableCount: Int,
    private val changed: Boolean,
    private val initiallyUnavailable: Boolean,
) : AutoCloseable {
    private val size = IntSize(2, 2)
    private val driver = Driver()
    private val device = NativeCanvasDevice(driver)
    private val producers = ArrayList<Producer>()
    private val trees = ArrayList<UiTree>()
    private val commands: List<DrawCommand>
    private val publicationCommands: List<DrawCommand>
    private val publicationReceipts: List<NativeCanvasSnapshot>
    private val publicationDeviceId: Long
    private val publicationBatchId: Long
    private val tokenAttachment = NativeCanvasToken::class.java.getDeclaredField("attachmentId").apply { check(trySetAccessible()) }
    private var phase = 0
    private val presentationConstructor =
        MethodHandles.publicLookup().findConstructor(
            NativeCanvasPresentation::class.java,
            MethodType.methodType(Void.TYPE, Long::class.java, Long::class.java, List::class.java, List::class.java, Boolean::class.java),
        )
    private val receiptConstructor =
        MethodHandles.publicLookup().findConstructor(
            NativeCanvasSnapshot::class.java,
            MethodType.methodType(Void.TYPE, NativeCanvasToken::class.java, DrawImage::class.java),
        )

    init {
        val requests =
            List(canvasCount) { index ->
                val source = device.source({ Producer(size, index).also(producers::add) })
                val tree = UiTree().also(trees::add)
                tree.update(evaluateComponentTree { Canvas(source, size) })
                tree.measure(Constraints.fixed(size.width, size.height))
                tree.layout()
                tree.paint().single()
            }
        commands =
            if (canvasCount == 0 && portableCount == 0) {
                emptyList()
            } else {
                buildList {
                    add(DrawCommand.PushClip(IntRect(0, 0, 1, 2)))
                    repeat(portableCount) { add(DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF102030.toInt()))) }
                    addAll(requests)
                    add(DrawCommand.PopClip)
                    add(DrawCommand.FillRectangle(IntRect(0, 1, 1, 2), ArgbColor(0xFF00FF00.toInt())))
                }
            }
        producers.forEach { it.available = initiallyUnavailable.not() }
        val primed = device.prepare(commands, FrameTime(1L), 1)
        publicationCommands = primed.drawCommands.toList()
        publicationDeviceId = NativeCanvasPresentation::class.java.getDeclaredField("deviceId").apply { check(trySetAccessible()) }.getLong(primed)
        publicationBatchId = NativeCanvasPresentation::class.java.getDeclaredField("batchId").apply { check(trySetAccessible()) }.getLong(primed)
        publicationReceipts =
            if (initiallyUnavailable) {
                emptyList()
            } else {
                val pixels = primed.capture().filterIsInstance<DrawCommand.BlitImagePixels>()
                primed.drawCommands.filterIsInstance<DrawCommand.Platform>().mapIndexed { index, command ->
                    receiptConstructor.invoke(command.command as NativeCanvasToken, pixels[index].image) as NativeCanvasSnapshot
                }
            }
        device.cancel(primed)
    }

    /**
     * Publishes fixed completed membership through the actual constructor, including identical handle dispatch on both sides.
     */
    fun publish(): NativeCanvasPresentation = presentationConstructor.invoke(publicationDeviceId, publicationBatchId, publicationCommands, publicationReceipts, initiallyUnavailable) as NativeCanvasPresentation

    /**
     * Prepares one admitted native batch and cancels it before another operation can replace live generations.
     */
    fun prepare(): List<DrawCommand> {
        if (canvasCount == 0) return commands
        return preparePresentation().drawCommands
    }

    private fun preparePresentation(): NativeCanvasPresentation {
        phase = 1 - phase
        producers.forEach {
            it.available = changed && initiallyUnavailable.not()
            it.phase = phase
        }
        val presentation = device.prepare(commands, FrameTime(2L), 1)
        device.cancel(presentation)
        return presentation
    }

    /**
     * Checks literal pixel order, immutable earlier generations, exact native membership and bounded target retention.
     * Initial absence must fail capture without returning a partially portable prefix.
     */
    fun verify() {
        val old = publish()
        check(old.drawCommands == publicationCommands)
        if (initiallyUnavailable) {
            check(runCatching { old.capture() }.exceptionOrNull() is IllegalStateException)
        } else {
            verifyCapture(old, imagePhase = 0)
        }
        val oldCommands = old.drawCommands.toList()
        val oldPixels = if (initiallyUnavailable) null else rasterizeHeadless(old.capture(), size).copyArgb()
        repeat(8) { index -> verifyNext(old, oldCommands, oldPixels, index) }
        close()
        check(device.retainedTargetCount() == 0)
        check(driver.liveTargets == 0)
        check(producers.all { it.closed })
        check(old.drawCommands == oldCommands)
        if (oldPixels != null) check(rasterizeHeadless(old.capture(), size).copyArgb().contentEquals(oldPixels))
    }

    private fun verifyNext(
        old: NativeCanvasPresentation,
        oldCommands: List<DrawCommand>,
        oldPixels: IntArray?,
        index: Int,
    ) {
        val next = if (canvasCount == 0) null else preparePresentation()
        val nextCommands = next?.drawCommands ?: prepare()
        check(nextCommands.filterIsInstance<DrawCommand.Platform>().size == if (initiallyUnavailable) 0 else canvasCount)
        check(device.retainedTargetCount() <= canvasCount * 2)
        check(old.drawCommands == oldCommands)
        if (oldPixels != null) check(rasterizeHeadless(old.capture(), size).copyArgb().contentEquals(oldPixels))
        if (canvasCount == 0) {
            check(nextCommands === commands)
        } else if (initiallyUnavailable) {
            check(runCatching { checkNotNull(next).capture() }.exceptionOrNull() is IllegalStateException)
        } else {
            val current = checkNotNull(next)
            verifyCapture(current, imagePhase = if (changed && index % 2 == 0) 1 else 0)
            val oldTokens = publicationCommands.filterIsInstance<DrawCommand.Platform>().map { it.command }
            val newTokens = current.drawCommands.filterIsInstance<DrawCommand.Platform>().map { it.command }
            check(
                oldTokens.zip(newTokens).all { (oldToken, newToken) ->
                    tokenAttachment.getLong(oldToken) == tokenAttachment.getLong(newToken) && (oldToken === newToken) == changed.not()
                },
            )
        }
    }

    private fun verifyCapture(
        presentation: NativeCanvasPresentation,
        imagePhase: Int,
    ) {
        val captured = presentation.capture()
        val images = captured.filterIsInstance<DrawCommand.BlitImagePixels>()
        check(images.size == canvasCount)
        images.forEachIndexed { index, command -> check(command.image === producers[index].images[imagePhase]) }
        verifyPixels(captured, producers.lastOrNull()?.images?.get(imagePhase)?.argbAt(0, 0))
    }

    private fun verifyPixels(
        captured: List<DrawCommand>,
        nativeColor: Int?,
    ) {
        val image = rasterizeHeadless(captured, size)
        val background = if (portableCount == 0) 0 else 0xFF102030.toInt()
        check(image.argbAt(0, 0) == (nativeColor ?: background))
        check(image.argbAt(1, 0) == 0)
        check(image.argbAt(1, 1) == 0)
        check(image.argbAt(0, 1) == if (canvasCount == 0 && portableCount == 0) 0 else 0xFF00FF00.toInt())
    }

    override fun close() {
        trees.forEach(UiTree::close)
        device.closeAfterGuiDiscarded()
    }

    /**
     * Immediately completed CPU fences; the device still exercises its real target and producer ownership protocol.
     */
    private class Driver : NativeCanvasDriver {
        var liveTargets = 0

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget {
            check(depth.not())
            liveTargets += 1
            return object : NativeCanvasTarget {
                override val size = physicalSize

                override fun close() {
                    liveTargets -= 1
                }
            }
        }

        override fun fence(): NativeCanvasFence =
            object : NativeCanvasFence {
                override fun isSignalled(): Boolean = true

                override fun close() = Unit
            }

        override fun finish() = Unit
    }

    /**
     * Alternates between two immutable CPU images while emitting a fresh capture lease for each changed generation.
     */
    private class Producer(
        size: IntSize,
        index: Int,
    ) : NativeCanvasProducer {
        val images = listOf(0xFF336600.toInt() or index, 0xFF884400.toInt() or index).map { color -> createDrawImage(size, IntArray(size.width * size.height) { color }) }
        var phase = 0
        var available = true
        var closed = false

        override fun capture(): NativeCanvasCapture? {
            if (available.not()) return null
            val image = images[phase]
            return object : NativeCanvasCapture {
                override fun render(
                    target: NativeCanvasTarget,
                    logicalSize: IntSize,
                    frameTime: FrameTime,
                ): DrawImage {
                    check(target.size == image.size && logicalSize == image.size)
                    check(frameTime == FrameTime(1L) || frameTime == FrameTime(2L))
                    return image
                }

                override fun close() = Unit
            }
        }

        override fun close() {
            check(closed.not())
            closed = true
        }
    }
}
