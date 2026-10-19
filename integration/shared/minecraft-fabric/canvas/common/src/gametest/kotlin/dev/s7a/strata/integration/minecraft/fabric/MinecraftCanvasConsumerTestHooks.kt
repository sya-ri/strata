package dev.s7a.strata.integration.minecraft.fabric

/**
 * Owns one render-thread callback pair around the real native GUI consumer.
 * Both callbacks are removed before user code, including reentrant and failing consumption.
 * Only the loaded-test artifact includes this hook.
 */
internal object MinecraftCanvasConsumerTestHooks {
    private var before: (() -> Unit)? = null
    private var after: (() -> Unit)? = null

    /**
     * Arms the next consumer with a close-before-read callback and its successful-return observation.
     */
    fun arm(
        beforeConsumer: () -> Unit,
        afterConsumer: () -> Unit,
    ) {
        check(before == null && after == null) { "A Canvas GUI-consumer test callback is already armed." }
        before = beforeConsumer
        after = afterConsumer
    }

    /**
     * Removes both callbacks, invokes the first and transfers the second to this consumer's wrapper.
     */
    fun beforeConsumer(): (() -> Unit)? {
        val currentBefore = before
        val currentAfter = after
        before = null
        after = null
        currentBefore ?: return null
        currentBefore()
        return currentAfter
    }

    /**
     * Drops an unconsumed pair without invoking it or changing native resource ownership.
     */
    fun reset() {
        before = null
        after = null
    }
}
