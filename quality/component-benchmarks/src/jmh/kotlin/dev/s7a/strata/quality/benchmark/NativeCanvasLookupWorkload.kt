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
import dev.s7a.strata.runtime.headless.HeadlessImage
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
import org.openjdk.jmh.infra.Blackhole
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Executes real current-batch and detached capture boundaries with deterministic immediate CPU driver fences.
 * A normal constructor handle creates detached test receipts without a runtime bridge or reimplemented lookup.
 * The fixture owns one current batch, fixed CPU images and retained trees; complete native presentation is separate evidence.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasLookupWorkload(
    private val targets: Int,
    private val occurrences: Int,
    portable: Int,
    private val changed: Boolean,
) : AutoCloseable {
    private val size = IntSize(2, 2)
    private val viewport = IntSize(16, 2)
    private val driver = Driver()
    private val device = NativeCanvasDevice(driver)
    private val producers = ArrayList<Producer>()
    private val trees = ArrayList<UiTree>()
    private val commands: List<DrawCommand>
    private val initial: NativeCanvasPresentation
    private val receipts: List<NativeCanvasSnapshot>
    private val captured: List<DrawCommand>
    private val tokens: List<NativeCanvasToken>
    private val deviceId: Long
    private val batchId: Long
    private var queued: NativeCanvasPresentation? = null
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
        val requests = List(targets) { index ->
            val source = device.source({ Producer(index).also(producers::add) })
            val tree = UiTree().also(trees::add)
            tree.update(evaluateComponentTree { Canvas(source, size) })
            tree.measure(Constraints.fixed(size.width, size.height))
            tree.layout()
            tree.paint().single() as DrawCommand.Platform
        }
        commands = buildList {
            add(DrawCommand.PushClip(IntRect(0, 0, 16, 2)))
            repeat(portable) { add(DrawCommand.FillRectangle(IntRect(0, 0, 16, 2), ArgbColor(0xFF102030.toInt()))) }
            repeat(occurrences) { index -> add(requests[index % targets].copy(bounds = IntRect(index % 8, 0, index % 8 + 2, 2))) }
            add(DrawCommand.PopClip)
            add(DrawCommand.FillRectangle(IntRect(0, 1, 1, 2), ArgbColor(0xFF00FF00.toInt())))
        }
        initial = device.prepare(commands, FrameTime(1L), 1)
        tokens = initial.drawCommands.filterIsInstance<DrawCommand.Platform>().map { it.command as NativeCanvasToken }
        captured = initial.capture()
        receipts = tokens.take(targets).mapIndexed { index, token -> receiptConstructor.invoke(token, producers[index].images[0]) as NativeCanvasSnapshot }
        deviceId = scalar("deviceId")
        batchId = scalar("batchId")
        device.queue(initial)
        queued = initial
    }

    private fun scalar(name: String): Long = NativeCanvasPresentation::class.java.getDeclaredField(name).apply { check(trySetAccessible()) }.getLong(initial)

    /** Borrows all current queued targets without rebuilding or resolving through current attachment state. */
    internal fun resolveTargets(sink: Blackhole) {
        tokens.forEach { sink.consume(device.target(checkNotNull(queued), it)) }
    }

    /** Measures full changed/unchanged preparation and queued submission, including index construction and settlement. */
    internal fun protocol(sink: Blackhole) {
        if (targets == 0) {
            sink.consume(commands)
            return
        }
        val current = prepareProtocol()
        current.drawCommands.forEach { command ->
            if (command is DrawCommand.Platform) sink.consume(device.target(current, command.command as NativeCanvasToken))
        }
        device.consumed()
    }

    private fun prepareProtocol(): NativeCanvasPresentation {
        if (queued != null) {
            device.consumed()
            queued = null
        }
        phase = 1 - phase
        producers.forEach {
            it.available = changed
            it.phase = phase
        }
        return device.prepare(commands, FrameTime(2L), 1).also(device::queue)
    }

    /** Captures the same old detached membership independently of device state and later generations. */
    internal fun capture(): List<DrawCommand> = initial.capture()

    /** Includes publication of immutable detached lists before exactly one actual capture. */
    internal fun captureOneShot(): List<DrawCommand> =
        (presentationConstructor.invoke(deviceId, batchId, initial.drawCommands, receipts, false) as NativeCanvasPresentation).capture()

    /** Returns fresh output pixels from primed portable commands without any receipt lookup. */
    internal fun rasterizePrepared(): HeadlessImage = rasterizeHeadless(captured, viewport)

    /** Returns fresh output pixels after an actual complete detached capture. */
    internal fun captureAndRasterize(): HeadlessImage = rasterizeHeadless(initial.capture(), viewport)

    /** Checks independent producer/occurrence association, native bounds and detached output after terminal device release. */
    internal fun verify() {
        val native = initial.drawCommands.filterIsInstance<DrawCommand.Platform>()
        val images = capture().filterIsInstance<DrawCommand.BlitImagePixels>()
        check(native.size == occurrences && images.size == occurrences)
        images.forEachIndexed { index, command ->
            check(command.image === producers[index % targets].images[0])
            check(command.destination == native[index].bounds)
            check(command.source == IntRect(0, 0, 2, 2))
            check(command.image.argbAt(0, 0) == (0xFF336600.toInt() or (index % targets)))
        }
        check(captureOneShot() == captured)
        val pixels = rasterizePrepared().copyArgb()
        check(captureAndRasterize().copyArgb().contentEquals(pixels))
        check(device.retainedTargetCount() == targets)
        val expectedTargets = driver.created.toList()
        tokens.forEachIndexed { index, token -> check(device.target(initial, token) === expectedTargets[index % targets]) }
        if (0 < targets) {
            repeat(8) {
                val current = prepareProtocol()
                val nativeCommands = current.drawCommands.filterIsInstance<DrawCommand.Platform>()
                val currentImages = current.capture().filterIsInstance<DrawCommand.BlitImagePixels>()
                check(nativeCommands.size == occurrences && currentImages.size == occurrences)
                nativeCommands.forEachIndexed { index, command ->
                    val token = command.command as NativeCanvasToken
                    check(device.target(current, token) === expectedTargets[index % targets])
                    check(currentImages[index].image === producers[index % targets].images[if (changed) phase else 0])
                    check((token === tokens[index]) == changed.not())
                }
                check(device.retainedTargetCount() == targets)
                device.consumed()
                check(initial.capture() == captured)
            }
        }
        close()
        check(device.retainedTargetCount() == 0 && driver.liveTargets == 0)
        check(producers.all { it.closed })
        check(initial.capture() == captured)
        check(captureAndRasterize().copyArgb().contentEquals(pixels))
    }

    override fun close() {
        trees.forEach(UiTree::close)
        device.closeAfterGuiDiscarded()
        queued = null
    }

    /** Immediate CPU completions exercise actual lifetime ownership without claiming GPU execution. */
    private class Driver : NativeCanvasDriver {
        val created = ArrayList<NativeCanvasTarget>()
        var liveTargets = 0

        override fun createTarget(physicalSize: IntSize, depth: Boolean): NativeCanvasTarget {
            check(depth.not())
            liveTargets += 1
            return object : NativeCanvasTarget {
                override val size = physicalSize

                override fun close() {
                    liveTargets -= 1
                }
            }.also(created::add)
        }

        override fun fence(): NativeCanvasFence =
            object : NativeCanvasFence {
                override fun isSignalled(): Boolean = true

                override fun close() = Unit
            }

        override fun finish() = Unit
    }

    /** Immutable phase images and fresh lease identities separate unchanged source reuse from changed generation protocol. */
    private class Producer(index: Int) : NativeCanvasProducer {
        val images = listOf(0xFF336600.toInt() or index, 0xFF884400.toInt() or index).map { color -> createDrawImage(IntSize(2, 2), IntArray(4) { color }) }
        var phase = 0
        var available = true
        var closed = false

        override fun capture(): NativeCanvasCapture? {
            if (available.not()) return null
            val image = images[phase]
            return object : NativeCanvasCapture {
                override fun render(target: NativeCanvasTarget, logicalSize: IntSize, frameTime: FrameTime): DrawImage {
                    check(target.size == image.size && logicalSize == image.size)
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
