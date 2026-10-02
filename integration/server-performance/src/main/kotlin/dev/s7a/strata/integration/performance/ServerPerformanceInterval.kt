package dev.s7a.strata.integration.performance

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.function.IntFunction

/**
 * Loads the selected unmodified testkit JAR separately from a real server plugin's bundled runtime.
 * Only collector and shared server adapter classes/resources resolve child-first; host classes retain the installed plugin loader.
 * All calls and terminal cleanup remain on the host's physical owner thread.
 */
public class ServerPerformanceInterval(
    kit: Path,
    name: String,
    operation: IntFunction<Int>,
    verify: IntConsumer,
    representatives: Map<String, String>,
    inputLabels: Set<String>,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val loader = isolatedLoader(kit)
    private val bridge =
        runCatching {
            val collector = Class.forName("dev.s7a.strata.performance.JvmPerformanceMeter", false, loader)
            check(
                Path
                    .of(
                        collector.protectionDomain.codeSource.location
                            .toURI(),
                    ).toRealPath() == kit.toRealPath(),
            ) { "Server collector resolved from another artifact" }
            Class
                .forName(ServerPerformanceBridge::class.java.name, true, loader)
                .getConstructor(String::class.java, IntFunction::class.java, IntConsumer::class.java, Map::class.java, Set::class.java)
                .newInstance(name, operation, verify, representatives, inputLabels)
        }.onFailure { failure -> runCatching(loader::close).exceptionOrNull()?.let(failure::addSuppressed) }
            .getOrThrow()
    private val advanceMethod = bridge.javaClass.getMethod("advance")
    private val writeMethod = bridge.javaClass.getMethod("write", String::class.java, String::class.java, String::class.java)
    private val closeMethod = bridge.javaClass.getMethod("close")
    private var closed = false

    /**
     * Advances the shared schedule without owning counters, warm-up or timers in the consumer.
     */
    public fun advance(): Boolean {
        check(Thread.currentThread() === owner && closed.not())
        return invoke(advanceMethod) as Boolean
    }

    /**
     * Delegates publication and collector binding after all required samples succeed.
     */
    public fun write(
        destination: Path,
        runId: String,
        host: String,
    ) {
        check(Thread.currentThread() === owner && closed.not())
        invoke(writeMethod, destination.toAbsolutePath().toString(), runId, host)
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        loader.use { invoke(closeMethod) }
    }

    private fun invoke(
        method: Method,
        vararg arguments: Any,
    ): Any? =
        try {
            method.invoke(bridge, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private fun isolatedLoader(kit: Path): URLClassLoader {
        val fixtureType = ServerPerformanceInterval::class.java
        val fixture =
            Path.of(
                fixtureType.protectionDomain.codeSource.location
                    .toURI(),
            )
        require(Files.isRegularFile(kit) && Files.isRegularFile(fixture)) { "Server measurements require actual collector and fixture JARs" }
        val prefixes = listOf("dev.s7a.strata.performance.", "${ServerPerformanceBridge::class.java.packageName}.")
        val resourcePrefixes = prefixes.map { it.replace('.', '/') }
        return object : URLClassLoader(arrayOf(kit.toUri().toURL(), fixture.toUri().toURL()), fixtureType.classLoader) {
            override fun loadClass(
                name: String,
                resolve: Boolean,
            ): Class<*> =
                synchronized(getClassLoadingLock(name)) {
                    if (prefixes.any(name::startsWith)) {
                        (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    } else {
                        super.loadClass(name, resolve)
                    }
                }

            override fun getResource(name: String): URL? = if (resourcePrefixes.any(name::startsWith)) findResource(name) else super.getResource(name)
        }
    }
}
