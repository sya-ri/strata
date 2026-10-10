package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the actual version-owned sampled manager with transferred CPU fake resources and frozen identity inputs.
 * Reflection decodes JVM method names only at this fixture boundary; it provides no copied poll or cache implementation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SampledLifetimePollingWorkload(
    private val count: Int,
    condition: NativeLifetimePollingBenchmark.Condition,
) : AutoCloseable {
    private val managerType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledImageDevice")
    private val driver = Driver()
    private val resources = ArrayList<Resource>()
    private val supports: (DrawImage) -> Boolean = { true }
    private val factory: (DrawImage, (NativeGuiResource) -> Unit) -> Any? =
        { _, retain ->
            Resource().also { resource ->
                resources.add(resource)
                retain(resource)
            }
            null
        }
    private val manager =
        managerType.declaredConstructors
            .single { it.parameterCount == 3 }
            .newInstance(driver, supports, factory)
    private val methods = managerType.declaredMethods.associateBy { it.name.substringBefore('$') to it.parameterCount }
    private val owners = ArrayList<Any>()
    private val images = ArrayList<List<DrawImage>>()
    private val hit: () -> Unit = {}
    private val miss: () -> Unit = {}
    private val uploaded: (DrawImage) -> Unit = {}
    private val evicted: () -> Unit = {}
    private var closed = false

    init {
        require(count in listOf(0, 1, 256, 512))
        check(managerType.classLoader === NativeCanvasDevice::class.java.classLoader)
        val archive = Path.of(checkNotNull(managerType.protectionDomain.codeSource).location.toURI())
        check(Files.isRegularFile(archive) && archive.fileName.toString().endsWith(".jar"))
        val ownerCount = if (256 < count) 2 else 1
        repeat(ownerCount) { index ->
            val owner = call("openOwner")
            owners.add(checkNotNull(owner))
            val size = minOf(256, count - index * 256)
            val sources = List(size) { image -> createDrawImage(IntSize(1, 1), intArrayOf(0xFF000000.toInt() or (index * 256 + image))) }
            images.add(sources)
            borrow(index, queued = false)
        }
        when (condition) {
            NativeLifetimePollingBenchmark.Condition.InitializationPending -> {}

            NativeLifetimePollingBenchmark.Condition.Settled -> {
                driver.signalAll()
                call("poll")
            }

            NativeLifetimePollingBenchmark.Condition.GuiPending -> {
                driver.signalAll()
                call("poll")
                owners.indices.forEach { borrow(it, queued = true) }
                call("consumed")
            }

            NativeLifetimePollingBenchmark.Condition.RetiredPending -> {
                retire()
            }

            NativeLifetimePollingBenchmark.Condition.AsynchronousDestruction -> {
                resources.forEach { it.destroyOnClose = false }
                driver.signalAll()
                call("poll")
                retire()
            }

            NativeLifetimePollingBenchmark.Condition.Quarantined -> {
                driver.signalAll()
                call("poll")
                owners.indices.forEach { borrow(it, queued = true) }
                call("failedGui")
            }

            NativeLifetimePollingBenchmark.Condition.ReloadPending -> {
                call("reload")
            }
        }
        verifyRetained()
    }

    /**
     * Polls the original manager implementation while its selected entries remain retained.
     */
    internal fun poll(): Int {
        call("poll")
        return retained()
    }

    /**
     * Selects immediate fake completions for the complete submission protocol only.
     */
    internal fun immediateCompletions() {
        driver.immediate = true
    }

    /**
     * Queues every actual cached source and settles actual GUI ownership without accumulating completion history.
     */
    internal fun submit(): Int {
        owners.indices.forEach { borrow(it, queued = true) }
        call("consumed")
        return retained()
    }

    /**
     * Retires all newly admitted owners before signalling their original pending initialization work.
     */
    internal fun retireAndSignal(): Int {
        retire()
        driver.signalAll()
        call("poll")
        check(retained() == 0)
        return retained()
    }

    /**
     * Requires exact current entry and payload counts rather than inferring successful admission from requested input size.
     */
    internal fun verifyRetained() {
        check(retained() == count && resources.size == count)
        check(call("retainedResourceBytes") == count.toLong() * 4L)
    }

    private fun retained(): Int = call("retainedResourceCount") as Int

    private fun borrow(
        index: Int,
        queued: Boolean,
    ) {
        val borrowed = checkNotNull(call("borrow", owners[index], images[index], hit, miss, uploaded, evicted))
        try {
            if (queued) {
                val queue =
                    borrowed.javaClass.declaredMethods
                        .single { it.parameterCount == 1 && it.parameterTypes[0] == DrawImage::class.java && it.returnType == Void.TYPE }
                images[index].forEach { image -> invoke(queue, borrowed, image) }
            }
        } finally {
            (borrowed as AutoCloseable).close()
        }
    }

    private fun retire() {
        owners.forEach { call("release", it) }
    }

    private fun call(
        name: String,
        vararg arguments: Any,
    ): Any? = invoke(checkNotNull(methods[name to arguments.size]), manager, *arguments)

    private fun invoke(
        method: Method,
        receiver: Any,
        vararg arguments: Any,
    ): Any? =
        try {
            method.invoke(receiver, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }

    override fun close() {
        if (closed) return
        closed = true
        call("beginShutdown")
        driver.finish()
        call("closeAfterFinish")
        resources.forEach { it.destroyed = true }
        driver.drainRetirements()
        call("acknowledgeAfterDrain")
        check(retained() == 0 && call("retainedResourceBytes") == 0L)
        check(driver.fences.isEmpty() && resources.all { it.destroyed && it.closeCalls == 1 })
    }

    private class Driver : NativeCanvasDriver {
        val fences = LinkedHashSet<Fence>()
        var immediate = false

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget = error("Sampled-source lifetime fixture allocated an unexpected target: $physicalSize, depth=$depth")

        override fun fence(): NativeCanvasFence = Fence(this, immediate).also(fences::add)

        override fun finish() {
            signalAll()
        }

        fun signalAll() {
            fences.forEach { it.signalled = true }
        }
    }

    private class Fence(
        private val driver: Driver,
        var signalled: Boolean,
    ) : NativeCanvasFence {
        override fun isSignalled(): Boolean = signalled

        override fun close() {
            check(driver.fences.remove(this))
        }
    }

    private class Resource : NativeGuiResource {
        var closeCalls = 0
        var destroyed = false
        var destroyOnClose = true

        override fun close() {
            check(closeCalls == 0)
            closeCalls += 1
            if (destroyOnClose) destroyed = true
        }

        override fun isDestroyed(): Boolean = destroyed
    }
}
