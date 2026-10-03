package dev.s7a.strata.quality.benchmark

import java.nio.file.Path

/**
 * Explicit staging entry point, separate from the gate that verifies immutable reviewed assignments.
 */
internal object PublishedHostInventoryCapture {
    /**
     * Writes a fresh prospective inventory after the compiler API and publication-model checks.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        PublishedHostInventoryEvidence.capture(Path.of(args[0]).toAbsolutePath().normalize(), Path.of(args[1]).toAbsolutePath().normalize())
    }
}
