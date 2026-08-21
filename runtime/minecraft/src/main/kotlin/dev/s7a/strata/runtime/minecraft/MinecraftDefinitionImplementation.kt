package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.text.UiText
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns construction and atomic transfer for private screen-definition implementations.
 */
internal object MinecraftDefinitionImplementation {
    /**
     * Creates one available definition without evaluating content.
     *
     * @param title exact unresolved title retained until transfer or close.
     * @param pausesGame whether the screen pauses the game.
     * @param content caller evaluator retained until transfer or close.
     * @return a new one-shot definition.
     */
    @JvmSynthetic
    fun create(
        title: UiText,
        pausesGame: Boolean,
        content: UiScope.() -> Unit,
    ): MinecraftScreenDefinition = ScreenDefinition.create(TransferredMinecraftDefinition.create(title, pausesGame, content))

    /**
     * Atomically removes the payload from an available private definition.
     *
     * @param definition definition to consume.
     * @return the uniquely transferred payload.
     * @throws IllegalStateException when the definition was transferred or closed.
     */
    @JvmSynthetic
    fun take(definition: MinecraftScreenDefinition): TransferredMinecraftDefinition =
        when (definition) {
            is ScreenDefinition -> definition.take()
        }

    private sealed interface State {
        class Available(
            val payload: TransferredMinecraftDefinition,
        ) : State

        data object Transferred : State

        data object Closed : State
    }

    private class ScreenDefinition private constructor(
        payload: TransferredMinecraftDefinition,
    ) : MinecraftScreenDefinition {
        private val state = AtomicReference<State>(State.Available(payload))

        fun take(): TransferredMinecraftDefinition {
            while (true) {
                when (val current = state.get()) {
                    is State.Available -> {
                        if (state.compareAndSet(current, State.Transferred)) {
                            return current.payload
                        }
                    }

                    State.Transferred -> {
                        throw IllegalStateException("Minecraft screen definition was already transferred.")
                    }

                    State.Closed -> {
                        throw IllegalStateException("Minecraft screen definition is closed.")
                    }
                }
            }
        }

        override fun close() {
            while (true) {
                when (val current = state.get()) {
                    is State.Available -> if (state.compareAndSet(current, State.Closed)) return
                    State.Transferred, State.Closed -> return
                }
            }
        }

        companion object {
            /**
             * Creates the private definition implementation.
             *
             * @param payload ownership carrier retained until transfer or close.
             * @return a new available definition.
             */
            @JvmSynthetic
            internal fun create(payload: TransferredMinecraftDefinition): ScreenDefinition = ScreenDefinition(payload)
        }
    }
}
