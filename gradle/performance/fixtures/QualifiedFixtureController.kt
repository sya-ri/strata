package independent.fixture

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Independent compiled control; never installed in a measured runtime archive.
 */
public object QualifiedFixtureController {
    /**
     * Decoded negative controls exercise actual process and receipt boundaries.
     */
    private enum class Behavior {
        Complete,
        Missing,
        Altered,
        Failure,
    }

    /**
     * Runs the complete pinned inventory and writes a receipt only after retaining actual observations.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Supply the pinned request" }
        val requestPath = Path.of(arguments[0])
        val request = JsonParser.parseString(Files.readString(requestPath)).asJsonObject
        val output = Path.of(request.get("output").asString)
        val origins = JsonObject()
        request.getAsJsonObject("required_classes").keySet().forEach { name ->
            Class.forName(name, false, ClassLoader.getSystemClassLoader())
            origins.add(name, JsonObject().apply { addProperty("pid", ProcessHandle.current().pid()) })
        }
        val results = JsonArray()
        request.getAsJsonArray("controls").forEach { entry ->
            executeControl(entry.asJsonObject, request, output, origins)?.let { results.add(it) }
        }
        val receipt =
            JsonObject().apply {
                addProperty("schema", "strata-paired-jvm-preflight-receipt-v1")
                addProperty("status", "passed")
                addProperty("request_sha256", hash(requestPath))
                add("controls", results)
            }
        Files.writeString(output.resolve("receipt.json"), receipt.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        println("Independent compiled controls completed: ${results.size()}")
        // Keep the actual application VM observable by the owning Job generation sampler.
        Thread.sleep(250)
    }

    /**
     * Retains one real observation before applying its decoded receipt or byte-tamper control.
     */
    private fun executeControl(
        control: JsonObject,
        request: JsonObject,
        output: Path,
        origins: JsonObject,
    ): JsonObject? {
        val id = control.get("id").asString
        val options = control.getAsJsonArray("arguments")
        val behavior = Behavior.valueOf(options[0].asString)
        val length = options[1].asInt
        require(length in 1..4096) { "Unbounded control fixture" }
        var sum = 0L
        repeat(length) { sum += it }
        val oracle = length.toLong() * (length - 1) / 2
        check(sum == oracle) { "Independent full-inventory parity failed" }
        val artifact = output.resolve("$id.json")
        val observation =
            JsonObject().apply {
                addProperty("actual_visits", length)
                addProperty("actual_sum", sum)
                addProperty("independent_sum", oracle)
            }
        Files.writeString(artifact, observation.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        check(behavior != Behavior.Failure) { "Required independent controller failure" }
        if (behavior == Behavior.Missing) return null
        if (behavior == Behavior.Altered) {
            val changed =
                Path.of(
                    request
                        .getAsJsonObject("required_classes")
                        .getAsJsonObject("org.openjdk.jmh.runner.Runner")
                        .get("path")
                        .asString,
                )
            Files.write(changed, byteArrayOf(1), StandardOpenOption.APPEND)
        }
        val retained =
            JsonObject().apply {
                addProperty("path", artifact.fileName.toString())
                addProperty("sha256", hash(artifact))
            }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("status", "passed")
            add("observations", observation)
            add("origins", origins.deepCopy())
            add("artifacts", JsonArray().apply { add(retained) })
        }
    }

    /**
     * Hashes retained bytes independently of the Python controller.
     */
    private fun hash(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
}
