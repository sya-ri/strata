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
        require(namespacePattern.matches(namespace)) { "Resource namespace is invalid." }
        require(pathPattern.matches(path)) { "Resource path is invalid." }
    }

    /**
     * Returns the canonical namespace-qualified resource spelling.
     *
     * @return `namespace:path`.
     */
    override fun toString(): String = "$namespace:$path"

    private companion object {
        private val namespacePattern: Regex = Regex("[a-z0-9_.-]+")
        private val pathPattern: Regex = Regex("(?!\\.{1,2}(?:/|$))(?!.*?/\\.{1,2}(?:/|$))[a-z0-9._-]+(?:/[a-z0-9._-]+)*")
    }
}
