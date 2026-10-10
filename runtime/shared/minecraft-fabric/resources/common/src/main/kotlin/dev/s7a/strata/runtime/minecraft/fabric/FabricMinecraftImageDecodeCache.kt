package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Reuses one private decoded pixel array after reading the current resource stream on every call.
 *
 * The complete key is manager identity, the captured lifecycle state, and exact encoded-byte equality.
 * The fixed decoder belongs to this instance and must produce detached straight-ARGB pixels.
 * At most one entry owns 8 MiB of encoded input and 16 MiB of encoded input plus ARGB payload.
 * Larger inputs and payloads decode normally without retention; no identifier, host, stream, native image, or returned image is retained.
 * Loads belong to the validated Minecraft client thread; native invalidation may run on any thread.
 * Reentrant loads may replace the current entry, but an older invocation cannot publish over their result.
 */
internal class FabricMinecraftImageDecodeCache(
    private val decode: (InputStream) -> Decoded,
) {
    private val maxEncodedBytes = 8 * 1024 * 1024
    private val maxRetainedBytes = 16L * 1024 * 1024
    private val current = AtomicReference(State())

    /**
     * Opens the current resource exactly once and returns a fresh immutable image after both closes.
     *
     * Stream read and close failures propagate normally and evict only the state captured by this invocation.
     * Reload or close during a callback prevents publication without changing the selected resource's result.
     * Terminal loads retain nothing and still perform ordinary source resolution and decoding.
     * The caller checks the client thread before invoking this internal helper.
     */
    @Suppress("TooGenericExceptionCaught") // Every source or snapshot failure must release the captured entry before propagation.
    @JvmSynthetic
    fun load(
        manager: Any,
        open: () -> InputStream,
    ): DrawImage {
        val initial = current.get()
        initial.entry?.requireOwner()
        try {
            val prepared =
                open().use { stream ->
                    try {
                        read(manager, stream, initial)
                    } finally {
                        closeQuietly(stream)
                    }
                }
            val image = prepared.decoded.snapshot()
            val retained = initial.entry
            if (retained != null && prepared.decoded === retained.decoded && prepared.encoded === retained.encoded) return image
            val encoded = prepared.encoded
            val entry =
                if (initial.terminal.not() && encoded != null && encoded.size.toLong() + prepared.decoded.payloadBytes <= maxRetainedBytes) {
                    Entry(manager, encoded, prepared.decoded)
                } else {
                    null
                }
            current.compareAndSet(initial, State(entry, initial.terminal))
            return image
        } catch (failure: Throwable) {
            current.compareAndSet(initial, State(terminal = initial.terminal))
            throw failure
        }
    }

    /**
     * Drops the matching entry, or advances an empty active-client generation before resource replacement.
     */
    @JvmSynthetic
    fun invalidate(
        manager: Any,
        activeClient: Boolean,
    ) {
        transition(manager, activeClient, terminal = false)
    }

    /**
     * Releases the active client's entry permanently; unrelated manager close can only evict its own entry.
     */
    @JvmSynthetic
    fun close(
        manager: Any,
        activeClient: Boolean,
    ) {
        transition(manager, activeClient, terminal = activeClient)
    }

    private fun read(
        manager: Any,
        stream: InputStream,
        initial: State,
    ): Prepared {
        if (initial.terminal) return Prepared(decode(BorrowedInput(stream)), null)
        val encoded = stream.readNBytes(maxEncodedBytes + 1)
        if (maxEncodedBytes < encoded.size) {
            val replay = PushbackInputStream(BorrowedInput(stream), encoded.size)
            replay.unread(encoded)
            return Prepared(decode(replay), null)
        }
        val previous = initial.entry
        if (previous != null && previous.manager === manager && encoded.contentEquals(previous.encoded)) {
            return Prepared(previous.decoded, previous.encoded)
        }
        return Prepared(decode(encoded.inputStream()), encoded)
    }

    private fun transition(
        manager: Any,
        activeClient: Boolean,
        terminal: Boolean,
    ) {
        var observed = current.get()
        while (observed.terminal.not()) {
            if (activeClient.not() && observed.entry?.manager !== manager) return
            if (current.compareAndSet(observed, State(terminal = terminal))) return
            observed = current.get()
        }
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (_: IOException) {
            // NativeImage.read also ignores an IOException from its internal stream close.
        }
    }

    /**
     * Takes ownership of a fresh decoder array and copies it into a distinct public image on every load.
     */
    class Decoded(
        private val size: IntSize,
        private val pixels: IntArray,
    ) {
        init {
            require(0 < size.width && 0 < size.height) { "Minecraft UI image dimensions must be positive." }
            require(size.width.toLong() * size.height == pixels.size.toLong()) { "Decoded Minecraft image payload must match its dimensions." }
        }

        /**
         * Exact private straight-ARGB payload charge, excluding temporary decoding work.
         */
        @get:JvmSynthetic
        val payloadBytes: Long
            get() = pixels.size.toLong() * Int.SIZE_BYTES

        /**
         * Copies owned pixels without exposing the retained array or retaining the returned image.
         */
        @JvmSynthetic
        fun snapshot(): DrawImage = createDrawImage(size, pixels)
    }

    private class Entry(
        val manager: Any,
        val encoded: ByteArray,
        val decoded: Decoded,
    ) {
        private val owner = Thread.currentThread()

        fun requireOwner() {
            check(Thread.currentThread() === owner) { "Minecraft UI image decoding must use its client thread." }
        }
    }

    private data class State(
        val entry: Entry? = null,
        val terminal: Boolean = false,
    )

    private class Prepared(
        val decoded: Decoded,
        val encoded: ByteArray?,
    )

    private class BorrowedInput(
        stream: InputStream,
    ) : FilterInputStream(stream) {
        override fun close() = Unit
    }
}
