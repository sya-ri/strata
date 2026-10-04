package dev.s7a.strata.integration.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmPerformanceEvidence
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.IntConsumer
import java.util.function.IntFunction
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises actual collector and fixture JARs across isolated plugin-style loaders, without a Minecraft server.
 */
public class ServerPerformanceIntervalTest {
    @Test
    public fun loadsSelectedCollectorAndReleasesBothArchivesAfterCompleteCollection() {
        withFixture { loader, directory ->
            val runId = UUID.randomUUID().toString()
            var operations = 0
            var verified = 0
            val interval =
                create(
                    loader,
                    IntFunction {
                        operations += 1
                        1
                    },
                    IntConsumer { verified += 1 },
                )
            try {
                assertFailsWith<InvocationTargetException> { call(interval, "write", directory.resolve("partial.json"), runId, "test-owner") }
                assertFalse(Files.exists(directory.resolve("partial.json")))
                CompletableFuture
                    .runAsync {
                        assertFailsWith<InvocationTargetException> { call(interval, "close") }
                    }.join()
                repeat(89) { assertEquals(false, call(interval, "advance")) }
                assertEquals(true, call(interval, "advance"))
                assertEquals(90, operations)
                assertEquals(60, verified)
                val report = directory.resolve("complete.json")
                call(interval, "write", report, runId, "test-owner")
                val json = Files.readString(report)
                assertTrue(json.contains("\"samples\": 60"))
                assertTrue(json.contains("\"collector_identity\""))
                assertTrue(json.contains("\"classTree\""))
                assertTrue(json.contains("\"host\": \"test-owner\""))
                assertFailsWith<InvocationTargetException> { call(interval, "write", report, runId, "test-owner") }
            } finally {
                call(interval, "close")
            }
            call(interval, "close")
            assertFailsWith<InvocationTargetException> { call(interval, "advance") }
        }
    }

    @Test
    public fun sharedProcessorAcceptsThreeIndependentScheduledInvocations() {
        withFixture { loader, directory ->
            val reports =
                (0..2).map { repetition ->
                    val runDirectory = Files.createDirectory(directory.resolve("run-$repetition"))
                    val report = runDirectory.resolve("test-interval.json")
                    val runId = UUID.randomUUID().toString()
                    listOf("test-interval", "test-secondary").forEach { name ->
                        val interval = create(loader, IntFunction { 1 }, IntConsumer {}, name)
                        try {
                            repeat(90) { call(interval, "advance") }
                            call(interval, "write", runDirectory.resolve("$name.json"), runId, "test-owner")
                        } finally {
                            call(interval, "close")
                        }
                    }
                    report
                }
            val result =
                JvmPerformanceReports.summarize(
                    reports,
                    Path.of(requireNotNull(System.getProperty("strata.test.performanceKit"))),
                    PerformanceReportContract(
                        "strata-test-owner-test-interval-v1",
                        listOf("name"),
                        1,
                        setOf("host", "warmup", "environment", "runtime_identity"),
                        setOf("samples"),
                    ),
                    listOf(PerformanceReportMetric("p50_ns", listOf("wall_p50_ns"))),
                )
            assertEquals("passed", result.get("status").asString)
            assertEquals(3, result.getAsJsonArray("sources").size())
            assertEquals(1, result.getAsJsonArray("phases").size())
            val request =
                JsonObject().apply {
                    addProperty("collector", requireNotNull(System.getProperty("strata.test.performanceKit")))
                    addProperty("output", directory.resolve("host-summary.json").toString())
                    add("runs", JsonArray().apply { reports.forEach { report -> add(checkNotNull(report.parent).toString()) } })
                }
            val requestPath = directory.resolve("host-request.json")
            PerformanceJson.writeNew(requestPath, request)
            val processorType = loader.loadClass(ServerPerformanceEvidence::class.java.name)
            val processor = processorType.getField("INSTANCE").get(null)
            val intervals = listOf("test-interval", "test-secondary")
            val contractType = loader.loadClass(ServerPerformanceContract::class.java.name)
            val contractConstructor = contractType.getConstructor(String::class.java, List::class.java, Map::class.java, Set::class.java)
            val contract = contractConstructor.newInstance("test-owner", intervals, mapOf("control" to "kotlin.Unit"), setOf("fixture"))
            val process = processorType.getMethod("process", Path::class.java, contractType, ClassLoader::class.java)
            process.invoke(processor, requestPath, contract, loader)
            val hostSummary = JvmPerformanceEvidence.readReport(directory.resolve("host-summary.json"))
            assertEquals("passed", hostSummary.get("status").asString)
            assertEquals(2, hostSummary.getAsJsonArray("workloads").size())
            assertTrue(hostSummary.getAsJsonArray("workloads").all { it.asJsonObject.getAsJsonArray("sources").size() == 3 })
            val unregistered = contractConstructor.newInstance("test-owner", intervals, mapOf("unregistered" to "kotlin.Unit"), setOf("fixture"))
            assertFailsWith<InvocationTargetException> {
                process.invoke(processor, requestPath, unregistered, loader)
            }
            val secondaryPath = checkNotNull(reports.first().parent).resolve("test-secondary.json")
            val originalSecondary = Files.readString(secondaryPath)
            val mixed = JvmPerformanceEvidence.readReport(secondaryPath)
            mixed.remove("source_receipt")
            mixed.addProperty("run_id", UUID.randomUUID().toString())
            PerformanceJson.write(secondaryPath, mixed)
            val mixedRequest = request.deepCopy().apply { addProperty("output", directory.resolve("mixed-summary.json").toString()) }
            val mixedRequestPath = directory.resolve("mixed-request.json")
            PerformanceJson.writeNew(mixedRequestPath, mixedRequest)
            assertFailsWith<InvocationTargetException> {
                process.invoke(processor, mixedRequestPath, contract, loader)
            }
            assertFalse(Files.exists(directory.resolve("mixed-summary.json")))
            Files.writeString(secondaryPath, originalSecondary)
            Files.writeString(directory.resolve("fixture-input.txt"), "Changed after server collection")
            val changedRequest = request.deepCopy().apply { addProperty("output", directory.resolve("changed-input-summary.json").toString()) }
            val changedRequestPath = directory.resolve("changed-input-request.json")
            PerformanceJson.writeNew(changedRequestPath, changedRequest)
            val changedFailure =
                assertFailsWith<InvocationTargetException> {
                    process.invoke(processor, changedRequestPath, contract, loader)
                }
            assertTrue(checkNotNull(changedFailure.targetException.message).contains("configuration file changed"))
            assertFalse(Files.exists(directory.resolve("changed-input-summary.json")))
            Files.copy(reports.first(), directory.resolve("copied.json"))
            assertFailsWith<IllegalArgumentException> {
                JvmPerformanceReports.summarize(
                    listOf(reports.first(), directory.resolve("copied.json"), reports.last()),
                    Path.of(requireNotNull(System.getProperty("strata.test.performanceKit"))),
                    PerformanceReportContract("strata-test-owner-test-interval-v1", listOf("name"), 1, setOf("host"), setOf("samples")),
                    emptyList(),
                )
            }
        }
    }

    @Test
    public fun operationFailureCannotCreateSuccessAndDoesNotRetainAnOpenLoader() {
        withFixture { loader, directory ->
            val interval = create(loader, IntFunction { error("warmup failed") }, IntConsumer {})
            try {
                val failure = assertFailsWith<InvocationTargetException> { call(interval, "advance") }
                assertEquals("warmup failed", failure.targetException.message)
                assertFailsWith<InvocationTargetException> { call(interval, "write", directory.resolve("failed.json"), UUID.randomUUID().toString(), "test-owner") }
                assertFalse(Files.exists(directory.resolve("failed.json")))
            } finally {
                call(interval, "close")
            }
        }
    }

    @Test
    public fun propertiesCommentsVaryWithoutChangingTheControlledSettings() {
        withFixture { loader, directory ->
            val first = directory.resolve("first.properties")
            val second = directory.resolve("second.properties")
            Files.writeString(first, "#first invocation\nseed=1\nport=25588\n")
            Files.writeString(second, "#second invocation\nport=25588\nseed=1\n")
            val type = loader.loadClass(ServerPerformanceInputs::class.java.name)
            val adapter = type.getField("INSTANCE").get(null)
            val identity = type.getMethod("identity", Map::class.java)
            val bytes = type.getMethod("byteIdentity", Map::class.java)
            assertEquals(identity.invoke(adapter, mapOf("configuration" to first)), identity.invoke(adapter, mapOf("configuration" to second)))
            assertTrue(bytes.invoke(adapter, mapOf("configuration" to first)) != bytes.invoke(adapter, mapOf("configuration" to second)))
            Files.writeString(second, "#second invocation\nport=25588\nseed=2\n")
            assertTrue(identity.invoke(adapter, mapOf("configuration" to first)) != identity.invoke(adapter, mapOf("configuration" to second)))
        }
    }

    private fun create(
        loader: ClassLoader,
        operation: IntFunction<Int>,
        verify: IntConsumer,
        name: String = "test-interval",
    ): Any =
        Class
            .forName(ServerPerformanceInterval::class.java.name, true, loader)
            .getConstructor(Path::class.java, String::class.java, IntFunction::class.java, IntConsumer::class.java, Map::class.java, Set::class.java)
            .newInstance(Path.of(requireNotNull(System.getProperty("strata.test.performanceKit"))), name, operation, verify, mapOf("control" to "kotlin.Unit"), setOf("fixture"))

    private fun call(
        interval: Any,
        name: String,
        vararg arguments: Any,
    ): Any? = interval.javaClass.getMethod(name, *arguments.map { if (it is Path) Path::class.java else String::class.java }.toTypedArray()).invoke(interval, *arguments)

    private fun withFixture(operation: (ClassLoader, Path) -> Unit) {
        val directory = Files.createTempDirectory("strata-server-performance-")
        val archive = directory.resolve("fixture.jar")
        val root = Path.of(requireNotNull(System.getProperty("strata.test.fixtureClasses")))
        val prefix = "dev/s7a/strata/integration/performance/"
        JarOutputStream(Files.newOutputStream(archive)).use { output ->
            Files.walk(root.resolve(prefix)).use { files ->
                files.filter(Files::isRegularFile).sorted().forEach { file ->
                    output.putNextEntry(JarEntry(root.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
        val loader =
            object : URLClassLoader(arrayOf(archive.toUri().toURL()), javaClass.classLoader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> =
                    if (name.startsWith(prefix.replace('/', '.'))) {
                        (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    } else {
                        super.loadClass(name, resolve)
                    }

                override fun getResource(name: String): URL? = if (name.startsWith(prefix)) findResource(name) else super.getResource(name)
            }
        val input = directory.resolve("fixture-input.txt")
        Files.writeString(input, "Stable fixture input")
        val manifest = directory.resolve("inputs.properties")
        Files.writeString(manifest, "fixture=${input.toString().replace('\\', '/')}\n")
        val previous = System.setProperty("strata.server.performanceInputs", manifest.toString())
        try {
            loader.use { operation(it, directory) }
        } finally {
            if (previous == null) System.clearProperty("strata.server.performanceInputs") else System.setProperty("strata.server.performanceInputs", previous)
        }
        // Windows rejects this deletion if either owned URL loader still retains the fixture archive.
        Files.delete(archive)
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
