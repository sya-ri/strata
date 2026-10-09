package dev.s7a.strata.performance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * Reads the actual fork's CPU, affinity, power and JDK sources outside JMH operation timing.
 */
internal object JmhCpuForkHost {
    /**
     * Requires a frozen probe and verifies its actual OS observations against the admitted executor profile.
     */
    internal fun capture(
        context: JsonObject,
        process: ProcessHandle,
        java: Path,
    ): JsonObject {
        val probe = Path.of(context.textField("executor_probe"))
        check(ArtifactIdentity.file(probe) == context.textField("executor_probe_sha256")) { "CPU host probe changed" }
        val python = Path.of(context.textField("python"))
        check(ArtifactIdentity.file(python) == context.objectField("executor_profile").textField("python_sha256")) { "CPU probe Python runtime changed" }
        val child = ProcessBuilder(python.toString(), probe.toString(), "--java", java.toString(), "--pid", process.pid().toString()).redirectErrorStream(true).start()
        try {
            val bytes = child.inputStream.use { it.readNBytes(1024 * 1024 + 1) }
            check(bytes.size <= 1024 * 1024 && child.waitFor() == 0) { "Actual CPU fork host observation failed" }
            val observed = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            check(observed.objectField("conditions") == context.objectField("executor_profile")) { "Actual fork CPU, affinity, power or JDK changed" }
            check(observed.objectField("sources").textField("probe_sha256") == context.textField("executor_probe_sha256")) { "Actual fork observer differs from frozen source" }
            return observed
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
