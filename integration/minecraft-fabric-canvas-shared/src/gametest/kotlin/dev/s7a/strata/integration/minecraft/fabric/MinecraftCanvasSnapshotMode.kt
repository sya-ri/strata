package dev.s7a.strata.integration.minecraft.fabric

/**
 * Selects whether a loaded native fixture provides capture receipts for its actual immutable output.
 *
 * Missing mode proves ordinary native rendering without a CPU fallback; matching mode tests explicit portable capture.
 */
internal enum class MinecraftCanvasSnapshotMode {
    /**
     * Native rendering supplies no CPU receipt and portable capture must fail.
     */
    Missing,

    /**
     * Every lease and callback supplies immutable pixels from that exact output generation.
     */
    Matching,
}
