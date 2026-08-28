package dev.s7a.strata.runtime.minecraft.font

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
