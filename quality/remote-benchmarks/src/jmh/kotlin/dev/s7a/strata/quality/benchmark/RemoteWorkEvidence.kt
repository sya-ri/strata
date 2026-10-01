package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Actual protocol workload and byte-parity admission without time thresholds or a second benchmark runner.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object RemoteWorkEvidence {
    /**
     * Checks all compiled combinations, exact sparse/full changes, immutable patch parity and real fragmentation.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(RemoteProtocolBenchmark::class.java), setOf("avgt")).size == 30)
        val benchmark = RemoteProtocolBenchmark()
        listOf(100, 8192).forEach { count ->
            RemoteChange.entries.forEach { change ->
                val state = RemoteProtocolBenchmark.Protocol()
                state.nodes = count
                state.change = change
                state.setup()
                val expected =
                    when (change) {
                        RemoteChange.Stable -> 0
                        RemoteChange.Single -> 1
                        RemoteChange.All -> count
                    }
                check(benchmark.diff(state).changed.size == expected)
                check(benchmark.apply(state) == state.after)
                check((benchmark.snapshotCodec(state) as RemoteMessage.Snapshot).tree == state.after)
                val encoded = state.codec.encode(state.update)
                check(state.codec.encode(benchmark.updateCodec(state)).contentEquals(encoded))
                check(benchmark.fragments(state).contentEquals(encoded))
                check(state.before.nodes.size == count)
                println("Remote protocol $count $change: $expected changed records, ${encoded.size} update bytes")
            }
        }
    }
}
