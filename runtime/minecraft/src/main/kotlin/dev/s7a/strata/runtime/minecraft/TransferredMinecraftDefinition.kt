package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.text.UiText

/**
 * Internal ownership carrier atomically removed from one screen definition.
 *
 * @property title exact unresolved title transferred to the host.
 * @property pausesGame whether the screen pauses the game.
 * @property content caller evaluator transferred to the core session.
 */
internal class TransferredMinecraftDefinition private constructor(
    @get:JvmSynthetic
    internal val title: UiText,
    @get:JvmSynthetic
    internal val pausesGame: Boolean,
    @get:JvmSynthetic
    internal val content: UiScope.() -> Unit,
) {
    /**
     * Owns the synthetic constructor bridge for the internal carrier.
     */
    companion object {
        /**
         * Creates one private transfer carrier without exposing its constructor to Java.
         *
         * @param title exact unresolved title.
         * @param pausesGame transferred pause policy.
         * @param content transferred application callback.
         * @return a new uniquely owned carrier.
         */
        @JvmSynthetic
        internal fun create(
            title: UiText,
            pausesGame: Boolean,
            content: UiScope.() -> Unit,
        ): TransferredMinecraftDefinition = TransferredMinecraftDefinition(title, pausesGame, content)
    }
}
