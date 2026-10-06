package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * Java-only entry point for collector-bound evidence requests supplied by consumers or Gradle tasks.
 * The sole argument is a UTF-8 JSON request file; JMH and the existing kit own the measurement engine.
 * Validation failures propagate to a nonzero process exit and cannot create a successful output.
 */
public object PerformanceEvidenceCli {
    /**
     * Executes one explicit request and publishes a new file without replacing existing evidence.
     * Requests contain command, collector, output, repetitions and either runs or baseline/candidate path arrays.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Provide one evidence request JSON file" }
        val request = JvmEvidenceFiles.document(Path.of(arguments.single()))
        val command = PerformanceEvidenceCommand.decode(request.textField("command"))
        val collector = Path.of(request.textField("collector"))
        val repetitions = if (request.has("repetitions")) request.countField("repetitions") else 3
        val report =
            when (command) {
                PerformanceEvidenceCommand.JmhSummary -> JmhPerformanceEvidence.summarize(paths(request, "runs"), collector, repetitions)
                PerformanceEvidenceCommand.JmhComparison -> JmhPerformanceEvidence.compare(paths(request, "baseline"), paths(request, "candidate"), collector, repetitions)
            }
        PerformanceJson.writeNew(Path.of(request.textField("output")), report)
    }

    private fun paths(
        request: JsonObject,
        key: String,
    ): List<Path> = request.arrayField(key).map { Path.of(it.textValue()) }
}
