package dev.s7a.strata.integration.minecraft.fabric

/**
 * Owns one render-thread capture after the actual native GUI consumer returns.
 * The one-shot callback is removed before invocation or reset; extraction never invokes it.
 * Only the loaded-test artifact includes this hook.
 */
internal object MinecraftCanvasCaptureTestHooks {
    private var callback: (() -> Unit)? = null

    /**
     * Transfers one capture callback until consumption or reset; rejects an already armed capture.
     */
    fun arm(afterConsumer: () -> Unit) {
        check(callback == null) { "A Canvas native-generation capture callback is already armed." }
        callback = afterConsumer
    }

    /**
     * Releases the callback before invoking it, including throwing and reentrant captures.
     */
    fun afterConsumer() {
        val current = callback
        callback = null
        current?.invoke()
    }

    /**
     * Drops an unconsumed callback without changing native resource ownership.
     */
    fun reset() {
        callback = null
    }
}
