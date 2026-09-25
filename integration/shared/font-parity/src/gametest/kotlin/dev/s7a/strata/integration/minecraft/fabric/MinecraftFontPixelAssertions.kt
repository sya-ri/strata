package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Collects unclassified native differences and exact Fabric differences before the gate fails.
 * One test thread owns this collector; it retains diagnostic messages only and never converts expected pixels into candidate inputs.
 * I/O and rendering failures propagate immediately; only explicit pixel comparison failures are accumulated.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftFontPixelAssertions(
    private val output: Path,
) {
    private val failures = mutableListOf<String>()

    /**
     * Writes the candidate and difference image, retaining any exact-pixel assertion failure for [verify].
     */
    fun compare(
        nativePath: Path,
        portable: HeadlessImage,
        candidatePath: Path,
    ) {
        try {
            MinecraftFontParityChecks.verifyPixels(nativePath, portable, candidatePath)
        } catch (failure: IllegalStateException) {
            failures += checkNotNull(failure.message)
        }
    }

    /**
     * Requires current native float evidence and classifies only final-output device differences.
     * Metrics and raw glyph pixels remain exact; failed or incomplete proofs cannot produce an acceptance receipt.
     */
    fun compareNative(
        nativePath: Path,
        portable: HeadlessImage,
        candidatePath: Path,
        profile: MinecraftUiProfile,
        scale: Int,
    ) {
        val result = MinecraftFontGpuImageComparison.compare(nativePath, portable, candidatePath, MinecraftFontParityFixture.commands(profile), scale)
        if (0 < result.unverified) failures += "Native font unclassified differences: ${result.unverified}/${result.differences}; first ${result.firstFailure}. See $nativePath."
    }

    /**
     * Fails after every capture when a native difference lacks a proof or any Fabric pixel differs.
     */
    fun verify() {
        val report = output.resolve("pixel-mismatches.txt")
        if (failures.isEmpty()) Files.deleteIfExists(report) else Files.write(report, failures)
        check(failures.isEmpty()) { failures.joinToString("\n", prefix = "Native font pixel mismatches:\n") }
    }
}
