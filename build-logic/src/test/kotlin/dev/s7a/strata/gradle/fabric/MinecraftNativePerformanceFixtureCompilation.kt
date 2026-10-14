package dev.s7a.strata.gradle.fabric

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/**
 * Compiles actual fixture sources with the JDK alone into this fresh test directory and returns its owned isolated loader.
 */
internal fun Path.compileNativePerformanceFixture(source: String): URLClassLoader {
    val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/performance-modules.tsv")) }
    val file = repository.resolve("integration/shared/minecraft-fabric/canvas/common/src/gametest/java/dev/s7a/strata/integration/minecraft/fabric/$source.java")
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
    compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { files ->
        check(compiler.getTask(null, files, null, listOf("-d", toString()), null, files.getJavaFileObjects(file.toFile())).call())
    }
    return URLClassLoader(arrayOf(toUri().toURL()), ClassLoader.getPlatformClassLoader())
}
