package dev.s7a.strata.integration.paper

import dev.s7a.strata.integration.performance.ServerPerformanceContract
import dev.s7a.strata.integration.performance.ServerPerformanceEvidence
import java.nio.file.Path

/**
 * Paper's exact workload entry point; the common server adapter checks archives through the shared kit.
 */
public object PaperPerformanceEvidence {
    /**
     * Processes three independent primary-owner invocations with their still-preserved plugin archives.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Provide one Paper evidence request" }
        ServerPerformanceEvidence.process(
            Path.of(arguments.single()),
            ServerPerformanceContract(
                "paper-primary-owner",
                PaperPerformanceWorkload.entries.map(PaperPerformanceWorkload::interval),
                PaperPerformanceWorkload.representatives,
                PaperPerformanceWorkload.inputLabels,
            ),
            javaClass.classLoader,
        )
    }
}
