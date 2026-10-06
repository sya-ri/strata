package dev.s7a.strata.performance

/**
 * Exact discovered API/module surface assignments and repository-relative impact ownership.
 * Discovery belongs to the host adapter; assignments must explicitly name every discovered symbol.
 * Adding an API member or module cannot inherit coverage through a package wildcard.
 *
 * @param surfaces actual module identifiers and their discovered public symbols.
 * @param assignments exact symbol-to-feature assignments reviewed alongside the executable workloads.
 * @param sourceOwners exact file paths or directory prefixes ending in `/`, mapped to affected features.
 */
public class PerformanceInventory(
    surfaces: Map<String, Set<String>>,
    assignments: Map<String, Map<String, String>>,
    sourceOwners: Map<String, Set<String>>,
) {
    private val surfaces = surfaces.mapValues { it.value.toSet() }
    private val assignments = assignments.mapValues { it.value.toMap() }
    private val sourceOwners = sourceOwners.mapValues { it.value.toSet() }

    /**
     * Rejects new, removed, unassigned, or orphaned symbols and owners before selecting workloads.
     * Every assigned feature must have an executable registration in the enclosing coverage gate.
     */
    public fun verify(registeredFeatures: Set<String>) {
        require(registeredFeatures.isNotEmpty() && registeredFeatures.all(String::isNotBlank))
        require(surfaces.isNotEmpty() && surfaces.keys.all(String::isNotBlank))
        require(surfaces.values.all { symbols -> symbols.isNotEmpty() && symbols.all(String::isNotBlank) })
        require(assignments.keys == surfaces.keys) { "Performance inventory module assignments changed" }
        surfaces.forEach { (module, symbols) ->
            val assigned = assignments.getValue(module)
            require(assigned.keys == symbols) {
                "Performance inventory changed in $module: unassigned=${symbols - assigned.keys}, stale=${assigned.keys - symbols}"
            }
            require(assigned.values.all { it in registeredFeatures }) { "Unregistered performance feature in $module" }
        }
        require(sourceOwners.isNotEmpty()) { "Performance source ownership is missing" }
        sourceOwners.forEach { (path, features) ->
            requireRelativePath(path.removeSuffix("/"))
            require(features.isNotEmpty() && features.all { it in registeredFeatures }) { "Unknown performance owner: $path" }
        }
        require(sourceOwners.values.flatten().toSet() == registeredFeatures) { "A performance feature has no source owner" }
    }

    /**
     * Returns the union of every matching owner; unknown or absent impact selects the full suite.
     * Paths use portable repository-relative separators, including deleted paths from a Git diff.
     */
    public fun affectedFeatures(
        registeredFeatures: Set<String>,
        changedPaths: Set<String>? = null,
    ): Set<String>? {
        verify(registeredFeatures)
        if (changedPaths == null) return null
        val affected = mutableSetOf<String>()
        changedPaths.forEach { path ->
            requireRelativePath(path)
            val matches = sourceOwners.filterKeys { owner -> path == owner || (owner.endsWith('/') && path.startsWith(owner)) }
            if (matches.isEmpty()) return null
            matches.values.forEach(affected::addAll)
        }
        return affected.toSet()
    }

    private fun requireRelativePath(path: String) {
        require(path.isNotBlank() && '\\' !in path && ':' !in path && path.none(Char::isISOControl)) { "Nonportable performance path: $path" }
        require(path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Unsafe performance path: $path" }
    }
}
