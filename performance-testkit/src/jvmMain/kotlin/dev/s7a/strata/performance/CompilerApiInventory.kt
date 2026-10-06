package dev.s7a.strata.performance

import java.nio.file.Files
import java.nio.file.Path

/**
 * Adapter for the compiler ABI dumps already verified by each published project's checkKotlinAbi task.
 * Keeps exact overloads and owners; source visibility differs from the separate loaded-JVM bytecode inventory.
 * This adapter performs no performance collection or member-execution inference.
 */
public object CompilerApiInventory {
    /**
     * Captures exact compiler declarations from caller-supplied, compiler-verified ABI dumps.
     * Each module needs at least one file; repeated files/declarations, unknown formats and empty inventories fail.
     * The caller owns compiler invocation and publication matching; this method does not certify binary origins or member execution.
     */
    public fun capture(modules: Map<String, List<Path>>): Map<String, Set<String>> {
        require(modules.isNotEmpty() && modules.keys.all(String::isNotBlank))
        return modules.mapValues { (_, files) ->
            require(files.isNotEmpty() && files.distinct().size == files.size)
            complete(files.flatMap(::read))
        }
    }

    /**
     * Reads a verified JVM or Klib ABI dump and rejects an empty declaration inventory.
     */
    private fun read(path: Path): Set<String> {
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".api")) { "Compiler ABI file is missing or has an unsupported format: $path" }
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        return if (path.fileName.toString().endsWith(".klib.api")) klib(lines) else jvm(lines)
    }

    /**
     * Preserves compiler signatures and declaration attributes for Klib public members.
     */
    @JvmSynthetic
    internal fun klib(lines: List<String>): Set<String> {
        val symbols = mutableListOf<String>()
        lines.map(String::trim).filter { it.isNotBlank() && it.startsWith("//").not() && it.contentEquals("}").not() }.forEach { line ->
            val separator = line.lastIndexOf(" // ")
            require(0 < separator) { "Unrecognized compiler Klib declaration: $line" }
            val declaration = line.substring(0, separator)
            val signature = line.substring(separator + 4)
            require(signature.isNotBlank()) { "Missing compiler Klib signature" }
            symbols.add("klib:$signature#$declaration")
        }
        return complete(symbols)
    }

    /**
     * Preserves every JVM public/protected declaration within its full declaring-class identity.
     */
    @JvmSynthetic
    internal fun jvm(lines: List<String>): Set<String> {
        val symbols = mutableListOf<String>()
        var owner: String? = null
        lines.map(String::trim).filter { it.isNotBlank() && it.startsWith("//").not() && it.startsWith('@').not() }.forEach { line ->
            when {
                line.contentEquals("}") -> {
                    check(owner != null) { "Unpaired compiler JVM class boundary" }
                    owner = null
                }

                line.endsWith('{') -> {
                    check(owner == null) { "Nested compiler JVM class declaration" }
                    owner = checkNotNull(Regex("\\bclass\\s+([^\\s:{]+)").find(line)?.groupValues?.get(1)) { "Unrecognized compiler JVM class: $line" }
                    symbols.add("jvm:$owner#$line")
                }

                line.startsWith("public ") || line.startsWith("protected ") -> {
                    val declaringClass = checkNotNull(owner) { "Compiler JVM member has no declaring class: $line" }
                    symbols.add("jvm:$declaringClass#$line")
                }

                else -> {
                    error("Unrecognized compiler JVM declaration: $line")
                }
            }
        }
        check(owner == null) { "Unclosed compiler JVM class" }
        return complete(symbols)
    }

    private fun complete(symbols: List<String>): Set<String> {
        require(symbols.isNotEmpty() && symbols.distinct().size == symbols.size) { "Empty or duplicate compiler API declarations" }
        return symbols.toSet()
    }
}
