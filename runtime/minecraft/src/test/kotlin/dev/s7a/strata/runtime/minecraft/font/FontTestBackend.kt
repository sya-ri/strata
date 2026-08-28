package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.render.DrawImage

/**
 * Test-thread CPU backend with injectable decoding, face creation, and cleanup failures.
 * Counters describe actual derived work and do not retain supplied font bytes.
 */
internal class FontTestBackend(
    private val decode: (ByteArray) -> DrawImage = { error("No PNG decoder was expected.") },
    private val open: (ByteArray, MinecraftTrueTypeSettings) -> MinecraftTrueTypeFace = { _, _ -> error("No TrueType face was expected.") },
    private val release: () -> Unit = {},
) : MinecraftFontBackend {
    /**
     * Number of attempted PNG decodes on this backend.
     */
    var decodeCalls = 0
        private set

    /**
     * Number of attempted face opens on this backend.
     */
    var openCalls = 0
        private set

    /**
     * Number of backend release attempts.
     */
    var closeCalls = 0
        private set

    override fun decodePng(bytes: ByteArray): DrawImage {
        decodeCalls++
        return decode(bytes)
    }

    override fun openTrueType(
        bytes: ByteArray,
        settings: MinecraftTrueTypeSettings,
    ): MinecraftTrueTypeFace {
        openCalls++
        return open(bytes, settings)
    }

    override fun close() {
        closeCalls++
        release()
    }
}
