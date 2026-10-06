package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Runs evidence verification from the actual packaged collector rather than certifying compiled test directories.
 */
internal class PackagedEvidenceFixture(
    val archive: Path = Path.of(requireNotNull(System.getProperty("strata.testkit.jar"))),
) : AutoCloseable {
    private val prefix = "dev.s7a.strata.performance."
    private val loader =
        object : URLClassLoader(arrayOf(archive.toUri().toURL()), javaClass.classLoader) {
            override fun loadClass(
                name: String,
                resolve: Boolean,
            ): Class<*> =
                if (name.startsWith(prefix)) {
                    (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                } else {
                    super.loadClass(name, resolve)
                }

            override fun getResource(name: String): URL? = if (name.startsWith(prefix.replace('.', '/'))) findResource(name) else super.getResource(name)
        }

    /**
     * Resolves a public boundary type from the selected archive for constructor-based contracts.
     */
    fun type(name: String): Class<*> = Class.forName(prefix + name, true, loader)

    /**
     * Invokes the public JDK/Gson boundary while preserving the verifier's actual failure type.
     */
    fun invoke(
        typeName: String,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg arguments: Any,
    ): Any? {
        val type = Class.forName(prefix + typeName, true, loader)
        return try {
            type.getMethod(methodName, *parameterTypes).invoke(type.getField("INSTANCE").get(null), *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    /**
     * Collects a detached summary through the packaged public API.
     */
    fun summarize(directories: List<Path>): JsonObject = invoke("JmhPerformanceEvidence", "summarize", arrayOf(List::class.java, Path::class.java, Int::class.java), directories, archive, 3) as JsonObject

    /**
     * Compares both preserved raw sides through the packaged public API.
     */
    fun compare(
        baseline: List<Path>,
        candidate: List<Path>,
    ): JsonObject = invoke("JmhPerformanceEvidence", "compare", arrayOf(List::class.java, List::class.java, Path::class.java, Int::class.java), baseline, candidate, archive, 3) as JsonObject

    override fun close(): Unit = loader.close()
}
