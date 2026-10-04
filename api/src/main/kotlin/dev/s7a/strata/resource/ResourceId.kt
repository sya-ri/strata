package dev.s7a.strata.resource

/**
 * Immutable resource-pack identifier shared by client and server code.
 *
 * The identifier owns only a validated namespace and resource-manager path.
 * It contains no pixels, open resource, or platform object and is safe to retain across threads.
 *
 * @property namespace lowercase namespace matching `[a-z0-9_.-]+`.
 * @property path lowercase slash-separated path matching `[a-z0-9/._-]+`.
 * @throws IllegalArgumentException when either part is empty or contains unsupported characters.
 */
public data class ResourceId(
    public val namespace: String,
    public val path: String,
) {
    init {
        require(namespace.isNotEmpty() && namespace.all(::partCharacter)) { "Resource namespace is invalid." }
        require(validPath(path)) { "Resource path is invalid." }
    }

    /**
     * Returns the canonical namespace-qualified resource spelling.
     *
     * @return `namespace:path`.
     */
    override fun toString(): String = "$namespace:$path"

    private companion object {
        private fun partCharacter(value: Char): Boolean = value in 'a'..'z' || value in '0'..'9' || value in "_.-"

        private fun validPath(path: String): Boolean {
            var start = 0
            for (index in path.indices) {
                if (path[index] == '/') {
                    if (validSegment(path, start, index).not()) return false
                    start = index + 1
                } else if (partCharacter(path[index]).not()) {
                    return false
                }
            }
            return validSegment(path, start, path.length)
        }

        private fun validSegment(
            path: String,
            start: Int,
            end: Int,
        ): Boolean {
            val length = end - start
            if (length == 0) return false
            if (length == 1 && path[start] == '.') return false
            return (length == 2 && path[start] == '.' && path[start + 1] == '.').not()
        }
    }
}
