package dev.s7a.strata.quality.benchmark

/**
 * Untimed debugger target for one real frozen transport cycle, with setup and cleanup outside the probe interval.
 */
public object TransportDrainProbeTarget {
    /**
     * Accepts a peer count and typed workload, then verifies that the observed cycle delivered all admitted work.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        TransportDrainFleet(args[0].toInt(), TransportDrainWorkload.valueOf(args[1])).use { fleet ->
            begin()
            fleet.cycle()
            end()
            fleet.verifyDrained()
        }
    }

    /**
     * Debugger interval marker; console output keeps a concrete executable method location.
     */
    @JvmStatic
    public fun begin() {
        println("Transport probe begin")
    }

    /**
     * Ends probing before verification and terminal cleanup.
     */
    @JvmStatic
    public fun end() {
        println("Transport probe end")
    }
}
