package dev.s7a.strata.performance

import kotlinx.browser.window
import kotlin.js.Promise

/**
 * Browser-native clock and animation-frame execution, independent of an application's renderer.
 * Action-to-next-frame latency is not GPU completion time or JavaScript CPU time.
 * Closing cancels pending callbacks, releases application closures, and rejects an incomplete run.
 *
 * @param plan successful warm-up and measured operations required by this run.
 */
public class BrowserPerformanceMeter(
    private val plan: PerformancePlan = PerformancePlan(),
) {
    private var active = false
    private var closed = false
    private var frame: Int? = null
    private var timeout: Int? = null
    private var rejectActive: ((Throwable) -> Unit)? = null
    private var cleanupActive: (() -> Unit)? = null

    /**
     * Waits for application readiness, then measures actions through the following animation frame.
     * Failure or timeout rejects the promise and cannot produce a successful interval.
     * Arbitrary application callback failures must cross the asynchronous boundary with their original cause.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun measure(
        ready: () -> Boolean = { true },
        beforeSample: (Int) -> Unit = {},
        afterSample: (Int) -> Unit = {},
        cleanup: () -> Unit = {},
        operation: (Int) -> Unit,
    ): Promise<BrowserPerformanceSample> {
        check(closed.not() && active.not()) { "Browser performance meter is closed or already active" }
        require(plan.preparationTimeoutMillis <= Int.MAX_VALUE)
        active = true
        cleanupActive = cleanup
        val values = mutableListOf<Long>()
        val operations = mutableListOf<Long>()
        var prepared = false
        return Promise { resolve, reject ->
            rejectActive = reject
            timeout = window.setTimeout({ fail(IllegalStateException("Browser performance interval timed out")) }, plan.preparationTimeoutMillis.toInt())

            fun next(index: Int) {
                try {
                    if (prepared.not()) {
                        prepared = ready()
                        if (prepared.not()) {
                            frame = window.requestAnimationFrame { if (active) next(index) }
                            return
                        }
                    }
                    beforeSample(index)
                    val before = window.performance.now()
                    operation(index)
                    val operationElapsed = ((window.performance.now() - before) * 1_000_000).toLong()
                    check(0 <= operationElapsed) { "Browser performance clock moved backwards" }
                    frame =
                        window.requestAnimationFrame {
                            if (active) {
                                try {
                                    if (plan.warmup <= index) {
                                        val elapsed = ((window.performance.now() - before) * 1_000_000).toLong()
                                        check(0 <= elapsed) { "Browser performance clock moved backwards" }
                                        values.add(elapsed)
                                        operations.add(operationElapsed)
                                    }
                                    afterSample(index)
                                    if (index + 1 == plan.warmup + plan.samples) {
                                        val result = BrowserPerformanceSample(PerformanceDistribution.of(operations), PerformanceDistribution.of(values))
                                        val cleanupFailure = release()
                                        if (cleanupFailure == null) resolve(result) else reject(cleanupFailure)
                                    } else {
                                        next(index + 1)
                                    }
                                } catch (failure: Throwable) {
                                    fail(failure)
                                }
                            }
                        }
                } catch (failure: Throwable) {
                    fail(failure)
                }
            }
            next(0)
        }
    }

    /**
     * Terminally releases this meter; pending preparation or samples remain a failed interval.
     */
    public fun close() {
        closed = true
        if (active) {
            fail(IllegalStateException("Browser performance interval was closed before completion"))
        } else {
            release()?.let { throw it }
        }
    }

    private fun fail(failure: Throwable) {
        val reject = rejectActive
        val cleanupFailure = release()
        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        reject?.invoke(failure)
    }

    private fun release(): Throwable? {
        val cleanup = cleanupActive
        cleanupActive = null
        active = false
        rejectActive = null
        frame?.let(window::cancelAnimationFrame)
        timeout?.let(window::clearTimeout)
        frame = null
        timeout = null
        return runCatching { cleanup?.invoke() }.exceptionOrNull()
    }
}
