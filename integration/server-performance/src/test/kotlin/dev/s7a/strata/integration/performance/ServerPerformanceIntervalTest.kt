package dev.s7a.strata.integration.performance

import dev.s7a.strata.performance.JvmPerformanceReports
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
                    val report = directory.resolve("run-$repetition.json")
                    val interval = create(loader, IntFunction { 1 }, IntConsumer {})
                    try {
                        repeat(90) { call(interval, "advance") }
                        call(interval, "write", report, UUID.randomUUID().toString(), "test-owner")
                    } finally {
                        call(interval, "close")
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

    private fun create(
        loader: ClassLoader,
        operation: IntFunction<Int>,
        verify: IntConsumer,
    ): Any =
        Class
            .forName(ServerPerformanceInterval::class.java.name, true, loader)
            .getConstructor(Path::class.java, String::class.java, IntFunction::class.java, IntConsumer::class.java, Map::class.java)
            .newInstance(Path.of(requireNotNull(System.getProperty("strata.test.performanceKit"))), "test-interval", operation, verify, mapOf("control" to "kotlin.Unit"))

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
        loader.use { operation(it, directory) }
        // Windows rejects this deletion if either owned URL loader still retains the fixture archive.
        Files.delete(archive)
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
