package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Verifies complete frame reuse and changed operations without requiring production diagnostic hooks.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ReactiveWorkEvidence {
    /**
     * Exercises every scene and reports optional legacy diagnostics as explicitly unavailable on current runtimes.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        println("scenario,frameReused,commands,diagnostics")
        ReactiveWorkload.entries.forEach { workload ->
            val state = ReactiveRenderingBenchmark.ReactiveSession()
            state.scenario = workload
            state.monitoring = true
            state.setup()
            try {
                val before = state.cleanFrame()
                val after = state.nextFrame()
                val expectsReuse = workload == ReactiveWorkload.Static || workload == ReactiveWorkload.MapEqual
                val reused = before === after
                check(expectsReuse.not() || reused) { "An unchanged presentation must reuse its frame for ${workload.name}." }
                check(state.cleanFrame() === after)
                println("${workload.name},$reused,${after.drawCommands.size},${state.workSnapshot()}")
            } finally {
                state.close()
            }
        }
    }
}
