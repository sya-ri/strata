package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceManager
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceOwnerId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Exercises real registry, device and portable-resource cleanup with deterministic CPU-only native callbacks.
 * The fixture issues no graphics API call or upload and cannot establish GPU time or native frame performance.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class NativeCleanupBenchmark {
    /**
     * Polls every registered owner, including an empty registry.
     */
    @Benchmark
    public fun registryPoll(state: Registry): Int = state.poll()

    /**
     * Settles GUI consumption and polls every registered idle owner.
     */
    @Benchmark
    public fun registryConsumed(state: Registry): Int = state.consumed()

    /**
     * Polls persistent unsignalled initialization fences without allocating new resources.
     */
    @Benchmark
    public fun pendingFencePoll(state: Pending): Int = state.poll()

    /**
     * Creates and releases complete generations whose initialization fences settle immediately.
     */
    @Benchmark
    public fun settledFenceCycle(state: Settled): Long = state.cycle()

    /**
     * Owns a fixed process-registry membership, with one counted cleanup participant per device.
     */
    @State(Scope.Thread)
    public open class Registry {
        /**
         * Zero, one or many independent driver identities.
         */
        @JvmField
        @Param("0", "1", "32")
        public var owners: Int = 0
        private val drivers = ArrayList<Driver>()
        private val managers = ArrayList<Manager>()

        /**
         * Actual manager and driver callback totals, read only outside supplemental sampling.
         */
        internal val observedWork: RegistryWork
            get() =
                RegistryWork(
                    managers.sumOf { it.polls.toLong() },
                    managers.sumOf { it.consumptions.toLong() },
                    drivers.sumOf { it.queries },
                    drivers.sumOf { it.fences },
                    drivers.sumOf { it.finishes.toLong() },
                    drivers.sumOf { it.drains.toLong() },
                    managers.sumOf { it.shutdowns.toLong() },
                    managers.sumOf { it.closes.toLong() },
                    managers.sumOf { it.acknowledgements.toLong() },
                )

        /**
         * Registers each driver once on this JMH worker; native storage remains empty.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(owners in setOf(0, 1, 32))
            while (drivers.size < owners) {
                val driver = Driver()
                val manager = Manager()
                NativeCanvasDevices.device(driver).registerGuiResourceManager(manager)
                drivers += driver
                managers += manager
            }
        }

        /**
         * Returns the actual owner count after all registry callbacks complete.
         */
        public fun poll(): Int {
            NativeCanvasDevices.poll()
            return managers.size
        }

        /**
         * Returns the actual owner count after consumption and independent device polling.
         */
        public fun consumed(): Int {
            NativeCanvasDevices.consumed()
            return managers.size
        }

        /**
         * Requires exactly one manager poll per operation and one consumed callback per consumption.
         */
        public fun verify() {
            val polls = managers.sumOf { it.polls }
            val consumed = managers.sumOf { it.consumptions }
            repeat(128) {
                check(poll() == owners)
                check(consumed() == owners)
            }
            check(managers.sumOf { it.polls } - polls == owners * 256)
            check(managers.sumOf { it.consumptions } - consumed == owners * 128)
            check(drivers.all { it.queries == 0L && it.fences == 0L && it.finishes == 0 })
            check(NativeCanvasDevices.retainedTargetCount() == 0)
            check(NativeCanvasDevices.retainedGuiResourceSetCount() == 0)
            println("native-registry,owners=$owners,polls=${owners * 256},consumptions=${owners * 128}")
        }

        /**
         * Performs the process registry's one permanent terminal transition on the same worker.
         */
        @TearDown(Level.Trial)
        public fun close() {
            NativeCanvasDevices.closeAfterGuiDiscarded()
            NativeCanvasDevices.closeAfterGuiDiscarded()
            check(drivers.all { it.finishes == 1 && it.drains == 1 })
            check(managers.all { it.shutdowns == 1 && it.closes == 1 && it.acknowledgements == 1 })
            check(NativeCanvasDevices.retainedManagedGuiResourceCount() == 0)
            check(runCatching { NativeCanvasDevices.device(Driver()) }.exceptionOrNull() is IllegalStateException)
        }
    }

    /**
     * Holds one current pending generation per owner until terminal completion.
     */
    @State(Scope.Thread)
    public open class Pending {
        /**
         * One or many independently initialized portable generations.
         */
        @JvmField
        @Param("1", "32")
        public var owners: Int = 1
        private val driver = Driver()
        private val device = NativeCanvasDevice(driver)

        /**
         * Actual query, fence, release and terminal callback totals, read outside supplemental sampling.
         */
        internal val observedWork: DeviceWork
            get() = DeviceWork(driver.queries, driver.fences, driver.fenceCloses, driver.resourceCloses, driver.finishes, driver.drains)

        /**
         * Creates pending fences before sampling without retiring any generation.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(owners in setOf(1, 32))
            repeat(owners) { driver.allocate(device, device.guiResources.createOwnerId(), retire = false) }
            verify()
        }

        /**
         * Polls the unchanged current pending set membership.
         */
        public fun poll(): Int {
            device.poll()
            return device.guiResources.retainedSetCount()
        }

        /**
         * Requires one query per pending identity, with no release or blocking finish.
         */
        public fun verify() {
            val queries = driver.queries
            repeat(128) { check(poll() == owners) }
            check(driver.queries - queries == owners * 128L)
            check(driver.fences == owners.toLong() && driver.fenceCloses == 0L && driver.resourceCloses == 0L)
            check(driver.finishes == 0 && driver.drains == 0)
            println("native-pending,owners=$owners,queries=${owners * 128L}")
        }

        /**
         * Completes and destroys every pending identity after discarding GUI queues.
         */
        @TearDown(Level.Trial)
        public fun close() {
            device.closeAfterGuiDiscarded()
            device.closeAfterGuiDiscarded()
            check(device.guiResources.retainedSetCount() == 0)
            check(driver.fenceCloses == owners.toLong() && driver.resourceCloses == owners.toLong())
            check(driver.finishes == 1 && driver.drains == 1)
        }
    }

    /**
     * Reuses presenter identities while each operation owns fresh settled resource generations.
     */
    @State(Scope.Thread)
    public open class Settled {
        /**
         * Complete generation cycles per operation.
         */
        @JvmField
        @Param("1", "32")
        public var owners: Int = 1
        private val driver = Driver().apply { signalled = true }
        private val device = NativeCanvasDevice(driver)
        private lateinit var identities: List<NativeGuiResourceOwnerId>

        /**
         * Actual query, fence, release and terminal callback totals, read outside supplemental sampling.
         */
        internal val observedWork: DeviceWork
            get() = DeviceWork(driver.queries, driver.fences, driver.fenceCloses, driver.resourceCloses, driver.finishes, driver.drains)

        /**
         * Current generation retention checked after each supplemental complete cycle.
         */
        internal val retainedSets: Int get() = device.guiResources.retainedSetCount()

        /**
         * Captures stable presenters and checks the same lifecycle outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(owners in setOf(1, 32))
            identities = List(owners) { device.guiResources.createOwnerId() }
            val closes = driver.resourceCloses
            val queries = driver.queries
            repeat(128) { cycle() }
            check(driver.resourceCloses - closes == owners * 128L)
            check(driver.queries - queries == owners * 128L)
            check(driver.fences == driver.fenceCloses && driver.fences == driver.resourceCloses)
            check(device.guiResources.retainedSetCount() == 0 && driver.finishes == 0)
            println("native-settled,owners=$owners,cycles=${owners * 128L}")
        }

        /**
         * Runs exact reserve, transfer, seal and retirement callbacks, retaining no completed generation.
         */
        public fun cycle(): Long {
            identities.forEach { owner -> driver.allocate(device, owner, retire = true) }
            return driver.resourceCloses
        }

        /**
         * Releases the empty device once after all measured cycles.
         */
        @TearDown(Level.Trial)
        public fun close() {
            check(device.guiResources.retainedSetCount() == 0)
            device.closeAfterGuiDiscarded()
            check(driver.finishes == 1 && driver.drains == 1)
        }
    }

    /**
     * Verifies each owner scope and exceptional device release through the generic automatic work hook.
     */
    public companion object {
        /**
         * Uses a single permanently terminal registry lifetime and standalone device lifetimes.
         */
        @JvmStatic
        public fun verifyWork(): Unit = verifyWithOwners({}, {}, {})

        /**
         * Exposes all ten complete native cleanup boundaries through the shared optional report runner.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = NativeCleanupOperationReport

        /**
         * Invokes synchronous report callbacks after original admission and before original terminal release.
         * The callbacks must not retain owners; registry membership grows only once in its original 0/1/32 order.
         * Failure in admission, a callback or cleanup prevents the caller from returning complete reports.
         */
        internal fun withVerifiedOwners(
            registry: (Registry) -> Unit,
            pending: (Pending) -> Unit,
            settled: (Settled) -> Unit,
        ): Unit = verifyWithOwners(registry, pending, settled)

        private fun verifyWithOwners(
            registryReport: (Registry) -> Unit,
            pendingReport: (Pending) -> Unit,
            settledReport: (Settled) -> Unit,
        ) {
            check(JmhWorkloadInventory.capture(listOf(NativeCleanupBenchmark::class.java), setOf("avgt")).size == 10)
            val registry = Registry()
            try {
                for (owners in listOf(0, 1, 32)) {
                    registry.owners = owners
                    registry.setup()
                    registry.verify()
                    registryReport(registry)
                }
            } finally {
                registry.close()
            }
            for (owners in listOf(1, 32)) {
                val pending = Pending().apply { this.owners = owners }
                try {
                    pending.setup()
                    pendingReport(pending)
                } finally {
                    pending.close()
                }
                val settled = Settled().apply { this.owners = owners }
                try {
                    settled.setup()
                    settledReport(settled)
                } finally {
                    settled.close()
                }
            }
            verifyFailures()
        }

        private fun verifyFailures() {
            val driver = Driver()
            val device = NativeCanvasDevice(driver)
            val first = Failure()
            val nested = Failure()
            val second = Failure()
            first.addSuppressed(nested)
            val managers = List(3) { Manager() }
            managers.forEach(device::registerGuiResourceManager)
            managers[0].pollAction = failing(first)
            managers[1].pollAction = failing(nested)
            managers[2].pollAction = failing(second)
            check(runCatching { device.poll() }.exceptionOrNull() === first)
            check(managers.all { it.polls == 1 })
            check(first.suppressed.size == 2 && first.suppressed[0] === nested && first.suppressed[1] === second)
            managers.forEach { it.pollAction = { } }
            managers[0].pollAction = { device.poll() }
            check(runCatching { device.poll() }.exceptionOrNull() is IllegalStateException)
            check(managers.all { it.polls == 2 })
            managers[0].pollAction = { }
            device.poll()
            check(managers.all { it.polls == 3 })
            managers[0].shutdownAction = failing(first)
            managers[1].closeAction = failing(second)
            check(runCatching { device.closeAfterGuiDiscarded() }.exceptionOrNull() === first)
            check(managers.all { it.shutdowns == 1 && it.closes == 1 && it.acknowledgements == 1 })
            check(driver.finishes == 1 && driver.drains == 1)
            check(runCatching { device.closeAfterGuiDiscarded() }.exceptionOrNull() === first)
            device.poll()
            check(managers.all { it.polls == 3 && it.closes == 1 })
            check(runCatching { device.guiResources.createOwnerId() }.exceptionOrNull() is IllegalStateException)
        }

        private fun failing(failure: Throwable): () -> Unit = { throw failure }
    }

    /**
     * Detached actual registry callback totals used by untimed before/after sample assertions.
     */
    internal data class RegistryWork(
        internal val polls: Long,
        internal val consumptions: Long,
        internal val queries: Long,
        internal val fences: Long,
        internal val finishes: Long,
        internal val drains: Long,
        internal val shutdowns: Long,
        internal val closes: Long,
        internal val acknowledgements: Long,
    )

    /**
     * Detached actual standalone-device callback totals used by untimed before/after sample assertions.
     */
    internal data class DeviceWork(
        internal val queries: Long,
        internal val fences: Long,
        internal val fenceCloses: Long,
        internal val resourceCloses: Long,
        internal val finishes: Int,
        internal val drains: Int,
    )

    /**
     * Counts simulated fence, physical release and terminal callbacks without storing their history.
     */
    private class Driver : NativeCanvasDriver {
        var signalled = false
        var queries = 0L
        var fences = 0L
        var fenceCloses = 0L
        var resourceCloses = 0L
        var finishes = 0
        var drains = 0
        private val extents = listOf(IntSize(2, 2))

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget = error("Cleanup fixture must not allocate Canvas targets.")

        override fun fence(): NativeCanvasFence {
            fences += 1
            return object : NativeCanvasFence {
                private var closed = false

                override fun isSignalled(): Boolean {
                    check(closed.not())
                    queries += 1
                    return signalled
                }

                override fun close() {
                    check(closed.not())
                    closed = true
                    fenceCloses += 1
                }
            }
        }

        override fun finish() {
            finishes += 1
            signalled = true
        }

        override fun drainRetirements() {
            drains += 1
        }

        /**
         * Transfers one current generation to the actual portable owner and optionally retires it.
         */
        fun allocate(
            device: NativeCanvasDevice,
            owner: NativeGuiResourceOwnerId,
            retire: Boolean,
        ) {
            val gui = device.guiResources
            val set = gui.reserve(owner, extents)
            gui.add(
                set,
                object : NativeGuiResource {
                    private var closed = false

                    override fun close() {
                        check(closed.not())
                        closed = true
                        resourceCloses += 1
                    }

                    override fun isDestroyed(): Boolean = closed
                },
            )
            gui.seal(set)
            if (retire) gui.release(set)
        }
    }

    /**
     * Counts each independent manager callback and exposes untimed failure injection.
     */
    private class Manager : NativeGuiResourceManager {
        var polls = 0
        var consumptions = 0
        var shutdowns = 0
        var closes = 0
        var acknowledgements = 0
        var pollAction: () -> Unit = { }
        var shutdownAction: () -> Unit = { }
        var closeAction: () -> Unit = { }

        override fun retainedResourceCount(): Int = 0

        override fun retainedResourceBytes(): Long = 0L

        override fun consumed() {
            consumptions += 1
        }

        override fun poll() {
            polls += 1
            pollAction()
        }

        override fun failedGui() = Unit

        override fun reload() = Unit

        override fun beginShutdown() {
            shutdowns += 1
            shutdownAction()
        }

        override fun closeAfterFinish() {
            closes += 1
            closeAction()
        }

        override fun acknowledgeAfterDrain() {
            acknowledgements += 1
        }
    }

    private class Failure : RuntimeException("native cleanup fixture", null, true, false) {
        override fun equals(other: Any?): Boolean = other is Failure

        override fun hashCode(): Int = 0
    }
}
