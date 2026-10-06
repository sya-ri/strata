package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.lang.management.ManagementFactory
import java.util.function.Predicate

/**
 * Actual Fabric extraction/render adapter, confined to the measured screen's client thread.
 * Event callbacks and native counters belong to the kit; consumers supply only state changes.
 * GPU completion, model mutation, readiness, and evidence storage are outside extraction samples.
 * Complete operation frames include diagnostic instrumentation and frame pacing, rather than uninstrumented application latency.
 *
 * @param screen actual measured Fabric screen, not a preview or replacement presenter.
 * @param refresh application-owned state delivery before extraction.
 */
public class MinecraftPerformanceMeter(
    screen: Any,
    refresh: () -> Unit = {},
) : AutoCloseable {
    // Names are the Fabric reflection contract, rather than application domain states.
    private val contextWaitMethod = "waitFor"
    private var screen: Any? = screen
    private var refresh: (() -> Unit)? = refresh
    private var active = false
    private var warmup = 0
    private var samples = 0
    private var fixture: NativePerformanceFixture? = null
    private var ready = false
    private var captured = false
    private var prepared = false
    private var settle = 0
    private var deadline = 0L
    private var sampling = false
    private var target = 0
    private var action: ((Int) -> Unit)? = null
    private var meter: JvmPerformanceMeter? = null
    private var monitor: RuntimeWorkAccumulator? = null
    private var windowGuard: NativeWindowGuard? = null
    private var baseline = emptyMap<String, Long>()
    private var result: JsonObject? = null
    private var failure: Throwable? = null
    private val thread = ManagementFactory.getThreadMXBean()
    private var frames: NativeFrameIntervals? = null
    private var abandonedFrame = false

    init {
        FabricPerformanceCallbacks.register(screen, listOf("beforeExtract", "beforeRender")) { before() }
        FabricPerformanceCallbacks.register(screen, listOf("afterExtract", "afterRender")) { after() }
    }

    /**
     * Starts one interval on the client's owner thread; no test-framework dependency is installed.
     */
    public fun begin(
        name: String,
        plan: PerformancePlan = PerformancePlan(),
        fixture: NativePerformanceFixture = NativePerformanceFixture(),
        update: (Int) -> Unit = {},
    ) {
        check(screen != null && active.not())
        windowGuard = NativeWindowGuard(checkNotNull(screen).javaClass.classLoader)
        this.fixture = fixture
        ready = false
        captured = false
        prepared = false
        settle = fixture.settleFrames
        sampling = false
        val timeout = Math.multiplyExact(plan.preparationTimeoutMillis, 1_000_000)
        require(timeout < Long.MAX_VALUE / 2)
        deadline = System.nanoTime() + timeout
        target = plan.samples
        warmup = plan.warmup
        samples = 0
        result = null
        failure = null
        frames = NativeFrameIntervals(target)
        abandonedFrame = false
        action = update
        meter = JvmPerformanceMeter(name, target)
        active = true
    }

    /**
     * Successful completion includes the following boundary of the final operation frame.
     * Callback failures propagate; elapsed waiting cannot satisfy this condition.
     */
    public val completed: Boolean
        get() {
            failure?.let { throw it }
            return active.not() && result != null
        }

    /**
     * Returns complete detached evidence after a successful client interval.
     */
    public fun result(): JsonObject {
        failure?.let { throw it }
        check(active.not())
        return checkNotNull(result).deepCopy()
    }

    /**
     * Detached progress for failure evidence; partial intervals never expose timing distributions.
     */
    public fun receipt(): JsonObject =
        JsonObject().apply {
            addProperty("samples", samples)
            addProperty("requested_samples", target)
            addProperty("remaining_warmup", warmup)
            addProperty("incomplete_extract", sampling)
            addProperty("incomplete_frame", abandonedFrame || frames?.pending == true)
            addProperty("complete", active.not() && result != null && failure == null)
            failure?.let { addProperty("failure", it.toString()) }
            if (active.not() && result != null && failure == null) add("measurement", checkNotNull(result).deepCopy())
        }

    /**
     * Runs with the installed Fabric ClientGameTestContext without coupling its version into the kit's POM.
     * Timeout and callback failures cancel diagnostic ownership on the actual client thread.
     * Arbitrary application and framework failures must cancel monitoring before being propagated.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun measure(
        context: Any,
        name: String,
        plan: PerformancePlan = PerformancePlan(),
        fixture: NativePerformanceFixture = NativePerformanceFixture(),
        update: (Int) -> Unit = {},
    ): JsonObject {
        try {
            FabricPerformanceCallbacks.onClient(context) { begin(name, plan, fixture, update) }
            val method = context.javaClass.methods.single { it.name == contextWaitMethod && it.parameterCount == 2 }
            val contract = Class.forName("net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext", true, context.javaClass.classLoader)
            val defaultTicks = contract.getField("DEFAULT_TIMEOUT").getInt(null).toLong()
            // Fabric defines DEFAULT_TIMEOUT as ten seconds; round up to a whole game tick.
            val timeout = Math.toIntExact(Math.addExact(Math.multiplyExact(defaultTicks, plan.preparationTimeoutMillis), 9_999) / 10_000)
            HostReflection.call(method, context, Predicate<Any> { completed }, timeout)
            var evidence: JsonObject? = null
            FabricPerformanceCallbacks.onClient(context) { evidence = result() }
            return checkNotNull(evidence)
        } catch (caught: Throwable) {
            try {
                FabricPerformanceCallbacks.onClient(context) { cancel() }
            } catch (cleanup: Throwable) {
                if (caught !== cleanup) caught.addSuppressed(cleanup)
            }
            throw caught
        }
    }

    /**
     * Clears every host and application callback reference after owner-thread cancellation.
     */
    override fun close() {
        cancel()
        screen = null
        refresh = null
        result = null
        failure = null
        abandonedFrame = false
    }

    private fun before() =
        observe {
            val wall = System.nanoTime()
            val cpu = currentCpu
            check(sampling.not()) { "Native presentation callbacks were not paired" }
            val spans = checkNotNull(frames)
            if (spans.pending) spans.complete(wall, cpu)
            val hooks = checkNotNull(fixture)
            checkNotNull(windowGuard).verify()
            hooks.validateFrame()
            check(System.nanoTime() - deadline < 0) { "Native performance interval timed out" }
            if (samples == target) {
                result =
                    checkNotNull(meter).result().apply {
                        spans.appendTo(this)
                        add("diagnostics", checkNotNull(monitor).snapshot())
                        NativePresentationCounters.append(this, checkNotNull(screen), baseline)
                    }
                hooks.afterSamples(checkNotNull(result).deepCopy())
                cancel()
                return@observe
            }
            if (ready.not()) ready = hooks.ready()
            if (ready.not() || prepare(hooks).not()) return@observe
            check(System.nanoTime() - deadline < 0) { "Native performance preparation timed out" }
            if (warmup == 0) spans.start(System.nanoTime(), currentCpu)
            checkNotNull(action)(samples + warmup)
            checkNotNull(refresh)()
            if (warmup == 0) {
                checkNotNull(meter).begin()
                sampling = true
            }
        }

    private val currentCpu: Long?
        get() = if (thread.isCurrentThreadCpuTimeSupported && thread.isThreadCpuTimeEnabled) thread.currentThreadCpuTime.takeIf { 0 <= it } else null

    private fun after() =
        observe {
            if (ready.not()) return@observe
            if (0 < warmup) {
                warmup -= 1
            } else if (0 < settle) {
                settle -= 1
                if (settle == 0) {
                    prepareMeasurement(checkNotNull(fixture))
                }
            } else if (sampling) {
                sampling = false
                checkNotNull(meter).end()
                checkNotNull(monitor).capture()
                samples += 1
            }
        }

    private fun prepare(hooks: NativePerformanceFixture): Boolean {
        if (0 < warmup) return true
        if (captured.not()) {
            hooks.captureAfterWarmup()
            captured = true
        }
        if (0 < settle) return false
        if (prepared.not()) prepareMeasurement(hooks)
        return true
    }

    private fun prepareMeasurement(hooks: NativePerformanceFixture) {
        hooks.beforeSamples()
        baseline = NativePresentationCounters.read(checkNotNull(screen))
        monitor = RuntimeWorkAccumulator(checkNotNull(screen))
        prepared = true
    }

    private fun cancel() {
        active = false
        action = null
        fixture = null
        sampling = false
        meter = null
        abandonedFrame = abandonedFrame || frames?.pending == true
        frames = null
        baseline = emptyMap()
        windowGuard = null
        val previous = monitor
        monitor = null
        previous?.close()
    }

    // Callback failures cross the render/test thread boundary and retain their original cause.
    @Suppress("TooGenericExceptionCaught")
    private fun observe(operation: () -> Unit) {
        if (active.not()) return
        try {
            operation()
        } catch (caught: Throwable) {
            failure = caught
            try {
                cancel()
            } catch (cleanup: Throwable) {
                if (caught !== cleanup) caught.addSuppressed(cleanup)
            }
        }
    }
}
