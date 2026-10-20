package dev.s7a.strata.integration.minecraft.fabric

/**
 * Reports the expected and actual fixture values when a loaded-client assertion fails.
 */
internal fun <T> assertTestValue(
    expected: T,
    actual: T,
    message: String,
) {
    if (expected != actual) throw AssertionError("$message Expected <$expected>, actual <$actual>.")
}
