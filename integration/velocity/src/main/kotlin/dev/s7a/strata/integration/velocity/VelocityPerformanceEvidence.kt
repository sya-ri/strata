package dev.s7a.strata.integration.velocity

import dev.s7a.strata.integration.performance.ServerPerformanceContract
import dev.s7a.strata.integration.performance.ServerPerformanceEvidence
import java.nio.file.Path

/**
 * Velocity's exact workload entry point; collection, aggregation and archive checks use the shared adapters.
 */
public object VelocityPerformanceEvidence {
    /**
     * Processes three independent post-backend-switch UI-owner invocations.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Provide one Velocity evidence request" }
        ServerPerformanceEvidence.process(
            Path.of(arguments.single()),
            ServerPerformanceContract(
                "velocity-ui-owner",
                VelocityPerformanceWorkload.entries.map(VelocityPerformanceWorkload::interval),
                VelocityPerformanceWorkload.representatives,
                VelocityPerformanceWorkload.inputLabels,
            ),
            javaClass.classLoader,
        )
    }
}
