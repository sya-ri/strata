package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmPerformanceMeter
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.results.AggregationPolicy
import org.openjdk.jmh.results.IterationResult
import org.openjdk.jmh.results.Result
import org.openjdk.jmh.results.ScalarResult

/**
 * Adapts the existing shared JDK meter to one JMH iteration without introducing a sampler or execution controller.
 * Process CPU includes benchmark workers, fork bookkeeping, JIT and GC during that iteration.
 * It is normalized by JMH's actual all-operation count, separately from elapsed latency and GC allocation.
 * The profiler controller thread's CPU is deliberately not reported as workload CPU.
 */
public class UnihexBoundsCpuProfiler : InternalProfiler {
    private var meter: JvmPerformanceMeter? = null

    override fun getDescription(): String = "Shared-meter fork process CPU per actual JMH iteration operation."

    override fun beforeIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
    ) {
        check(meter == null)
        meter = JvmPerformanceMeter(benchmarkParams.benchmark, 1).also { it.begin() }
    }

    override fun afterIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
        result: IterationResult,
    ): Collection<Result<*>> {
        val current = checkNotNull(meter)
        current.end()
        meter = null
        val cpu = current.result().get("process_cpu_ns")
        check(cpu != null && cpu.isJsonNull.not()) { "Qualified host must expose process CPU time" }
        val operations = result.metadata.allOps
        check(0 < operations)
        return listOf(ScalarResult("unihex.processCpu", cpu.asLong.toDouble() / operations, "ns/op", AggregationPolicy.AVG))
    }
}
