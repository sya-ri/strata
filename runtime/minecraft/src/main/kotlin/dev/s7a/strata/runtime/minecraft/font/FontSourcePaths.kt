package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId

/**
 * Validates an external pack-relative path before a source resolves it.
 *
 * @receiver supplied path.
 * @return the unchanged canonical relative path.
 * @throws IllegalArgumentException when the path is empty, absolute, or contains traversal or backslashes.
 */
internal fun String.checkedFontSourcePath(): String {
    require(isNotEmpty() && startsWith('/').not() && contains('\\').not() && contains(':').not()) {
        "Font asset paths must be canonical relative paths."
    }
    require(split('/').all { segment -> segment.isNotEmpty() && (segment.length <= 2 && segment.all { character -> character == '.' }).not() }) {
        "Font asset paths cannot contain empty or parent-traversal segments."
    }
    return this
}

/**
 * Converts a namespace-relative asset path into a detached resource identifier without retaining source state.
 * Only identifier validation failures become absence; unexpected failures propagate on the calling thread.
 *
 * @receiver path below the pack's assets directory, beginning with its namespace.
 * @return the identifier, or null when either path component is missing or unsupported.
 */
internal fun String.fontResourceIdentifier(): ResourceId? {
    val separator = indexOf('/')
    if (separator !in 1 until lastIndex) return null
    return runCatching { ResourceId(substring(0, separator), substring(separator + 1)) }.getOrElse { failure ->
        if (failure is IllegalArgumentException) null else throw failure
    }
}
