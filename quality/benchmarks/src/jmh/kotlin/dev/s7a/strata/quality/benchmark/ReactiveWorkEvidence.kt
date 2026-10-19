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
                val reused = workload == ReactiveWorkload.Static || workload == ReactiveWorkload.MapEqual
                check((before === after) == reused) { "Unexpected presentation reuse for ${workload.name}." }
                // Virtual-list anchor preservation can publish a scroll revision for the following frame.
                val settled = state.cleanFrame()
                check(state.cleanFrame() === settled) { "The presentation must settle after ${workload.name}." }
                println("${workload.name},$reused,${after.drawCommands.size},${state.workSnapshot()}")
            } finally {
                state.close()
            }
        }
    }
}
