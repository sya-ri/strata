package dev.s7a.strata.quality

/**
 * Loads deliberately valid or invalid Kotlin fixtures as test data rather than executable test declarations.
 */
internal object DetektTestSources {
    /**
     * Reads one UTF-8 fixture beneath `source-fixtures` without changing its whitespace or line endings.
     * Missing fixtures fail before a rule can analyze a substituted or empty input.
     */
    fun read(name: String): String {
        val resource = checkNotNull(javaClass.getResourceAsStream("/source-fixtures/$name.kt.txt")) { "Missing Detekt source fixture: $name" }
        return resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
