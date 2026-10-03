package dev.s7a.strata.performance

import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Bounded complete operation-frame intervals, with preparation gaps outside every recorded span.
 * Each sample starts after untimed preparation and ends at the next presentation callback, including the final sample.
 * The native owner supplies JDK clock readings; this recorder never obtains a clock or performs application work.
 */
internal class NativeFrameIntervals(
    private val expected: Int,
) {
    private var startWall: Long? = null
    private var startCpu: Long? = null
    private val walls = mutableListOf<Long>()
    private val cpus = mutableListOf<Long?>()

    init {
        require(0 < expected)
    }

    /**
     * Whether a started sample still needs its following presentation boundary.
     */
    internal val pending: Boolean
        get() = startWall != null

    /**
     * Starts exactly one span after readiness, evidence capture and application snapshots.
     */
    internal fun start(
        wall: Long,
        cpu: Long?,
    ) {
        check(pending.not() && walls.size < expected)
        require(cpu == null || 0 <= cpu)
        startWall = wall
        startCpu = cpu
    }

    /**
     * Completes the preceding span; nano-time wrap is preserved by relative subtraction.
     */
    internal fun complete(
        wall: Long,
        cpu: Long?,
    ) {
        val previousWall = checkNotNull(startWall)
        val previousCpu = startCpu
        val elapsed = wall - previousWall
        require(0 <= elapsed && (cpu == null || 0 <= cpu))
        val elapsedCpu = if (previousCpu == null || cpu == null) null else (cpu - previousCpu).also { require(0 <= it) }
        walls.add(elapsed)
        cpus.add(elapsedCpu)
        startWall = null
        startCpu = null
    }

    /**
     * Publishes complete shared distributions; one unavailable CPU reading makes that metric unavailable.
     */
    internal fun appendTo(result: JsonObject) {
        check(pending.not() && walls.size == expected && cpus.size == expected)
        result.add("frame_interval", PerformanceJson.distribution(walls))
        result.add("render_thread_frame_cpu", if (cpus.any { it == null }) JsonNull.INSTANCE else PerformanceJson.distribution(cpus.map(::checkNotNull)))
    }
}
