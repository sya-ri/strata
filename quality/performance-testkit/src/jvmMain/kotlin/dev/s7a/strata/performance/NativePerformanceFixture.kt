package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Application data and evidence hooks outside native extraction samples.
 * The kit owns invocation order, settling, deadlines, counters, and callback cleanup.
 * Hooks execute on the screen's owner thread and failures invalidate the whole interval.
 *
 * @param ready preparation predicate; false waits for another presented frame.
 * @param captureAfterWarmup expensive fixture inventory capture before settling.
 * @param settleFrames untimed presentations after capture, without application updates.
 * @param beforeSamples snapshot application data after settling and before measured updates.
 * @param afterSamples receive detached complete evidence outside the timed interval.
 * @param validateFrame verify fixture/window ownership before every frame.
 */
public class NativePerformanceFixture(
    public val ready: () -> Boolean = { true },
    public val captureAfterWarmup: () -> Unit = {},
    public val settleFrames: Int = 0,
    public val beforeSamples: () -> Unit = {},
    public val afterSamples: (JsonObject) -> Unit = {},
    public val validateFrame: () -> Unit = {},
) {
    init {
        require(0 <= settleFrames)
    }
}
