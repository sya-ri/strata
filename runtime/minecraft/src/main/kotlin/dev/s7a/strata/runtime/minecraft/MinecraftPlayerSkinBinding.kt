package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread retained binding for one asynchronously resolved player skin.
 *
 * An adapter may complete network or cache work on any thread, but it publishes changed snapshots and invokes the sole observer only from [MinecraftUiPlatform.refresh] on the host owner thread.
 * Closing is owner-thread confined and makes later queued completions inert.
 */
@InternalStrataRuntimeApi
public interface MinecraftPlayerSkinBinding : AutoCloseable {
    /**
     * Returns the latest snapshot committed at a frame boundary.
     *
     * @return pending, ready, or failed state owned by this binding.
     * @throws IllegalStateException when called from another thread or after close.
     */
    public fun snapshot(): Snapshot

    /**
     * Installs the sole retained-node observer.
     *
     * @param observer callback invoked on the owner thread after a distinct frame-boundary snapshot commit.
     * @return idempotent owner-thread subscription release.
     * @throws IllegalStateException when called from another thread, after close, or while another observer is live.
     */
    public fun observe(observer: () -> Unit): AutoCloseable

    /**
     * Releases this lookup and makes late asynchronous completions inert.
     *
     * Close is idempotent and owner-thread confined.
     */
    override fun close()

    /**
     * Immutable frame-boundary skin lookup state.
     */
    public sealed interface Snapshot {
        /**
         * Lookup has not committed a result.
         */
        public data object Pending : Snapshot

        /**
         * Lookup resolved one normalized skin.
         *
         * @property skin immutable 64 by 64 pixels.
         */
        public data class Ready(
            public val skin: DrawImage,
        ) : Snapshot

        /**
         * Lookup completed without a usable skin.
         */
        public data object Failed : Snapshot
    }
}
