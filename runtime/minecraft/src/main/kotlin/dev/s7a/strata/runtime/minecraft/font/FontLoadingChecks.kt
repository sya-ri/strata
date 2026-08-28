package dev.s7a.strata.runtime.minecraft.font

/**
 * Checks a non-negative quantity without subtraction or multiplication overflow.
 *
 * @param actual observed quantity.
 * @param maximum inclusive ceiling.
 * @param subject detached diagnostic description, never a behavior discriminator.
 * @throws MinecraftFontLoadLimitException when the ceiling is exceeded.
 */
internal fun requireFontLimit(
    actual: Long,
    maximum: Long,
    subject: String,
) {
    if (maximum < actual) throw MinecraftFontLoadLimitException("Font loading limit exceeded for $subject: $actual exceeds $maximum.")
}

/**
 * Verifies a caller-supplied path set before the loader makes additional collections.
 * Custom sources remain responsible for bounding allocation inside their original enumeration method.
 *
 * @param paths source-owned path set, never mutated.
 * @param limits inclusive entry and path ceilings.
 * @return the same caller-readable set after validation.
 * @throws IllegalArgumentException when a count or path length exceeds its ceiling.
 */
internal fun checkedFontPaths(
    paths: Set<String>,
    limits: MinecraftFontLoadLimits,
): Set<String> {
    requireFontLimit(paths.size.toLong(), limits.maxSourceEntries.toLong(), "source entries")
    paths.forEach { path -> requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "source path length") }
    return paths
}
