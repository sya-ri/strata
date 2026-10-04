package dev.s7a.strata.integration.web

import dev.s7a.strata.performance.BrowserPerformanceMeter
import dev.s7a.strata.performance.PerformancePlan
import kotlin.js.Promise

/**
 * Loaded-browser regression probes for failure evidence and collector cleanup, separate from timed fixtures.
 */
internal object WebPerformanceCollectorCheck {
    /**
     * Rejects operation and terminal cleanup failures with their actual cause and one cleanup invocation.
     */
    fun verify(): Promise<Boolean> =
        Promise.all(arrayOf(failure(cleanupFails = false), failure(cleanupFails = true))).then { values ->
            check(values.all { it })
            true
        }

    private fun failure(cleanupFails: Boolean): Promise<Boolean> {
        val expected = IllegalArgumentException("collector fixture failure")
        var cleanups = 0
        val meter = BrowserPerformanceMeter(PerformancePlan(warmup = 0, samples = 1))
        return meter
            .measure(
                cleanup = {
                    cleanups += 1
                    if (cleanupFails) throw expected
                },
            ) {
                if (cleanupFails.not()) throw expected
            }.then(
                onFulfilled = { error("A failed operation or cleanup produced successful performance evidence") },
                onRejected = { failure ->
                    check(failure === expected)
                    check(cleanups == 1)
                    meter.close()
                    check(cleanups == 1)
                    true
                },
            )
    }
}
