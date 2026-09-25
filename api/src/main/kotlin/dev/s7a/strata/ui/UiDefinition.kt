package dev.s7a.strata.ui

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.screen.ScreenDefinitionUnavailableException
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.UiPresenters
import dev.s7a.strata.text.UiText
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One-shot UI contents and opening settings, without retaining a live session.
 * Construction does not evaluate content. Transfer and close may race; exactly one wins ownership.
 * The optional title is a narration name and is not automatically drawn inside the UI.
 * [pausesGame] only takes effect when presented as a screen.
 */
@OptIn(InternalStrataRuntimeApi::class, ExperimentalAtomicApi::class)
public class UiDefinition(
    title: UiText? = null,
    public val presentation: UiPresentation = UiPresentation.Screen,
    public val category: UiCategory? = null,
    public val inputPolicy: UiInputPolicy = UiInputPolicy.BlockAll,
    public val visibility: UiVisibilityPolicy = UiVisibilityPolicy(),
    public val hudOrder: Int = 0,
    public val pausesGame: Boolean = false,
    content: UiScope.() -> Unit,
) : AutoCloseable {
    private val state =
        AtomicReference<State>(
            State.Available(
                UiDefinitionPayload(
                    title ?: UiText.Translated("strata.ui.title", "Strata interface"),
                    presentation,
                    category,
                    inputPolicy,
                    visibility,
                    hudOrder,
                    pausesGame,
                    content,
                ),
            ),
        )

    /**
     * Creates a definition with a literal narration title.
     */
    public constructor(
        title: String,
        presentation: UiPresentation = UiPresentation.Screen,
        category: UiCategory? = null,
        inputPolicy: UiInputPolicy = UiInputPolicy.BlockAll,
        visibility: UiVisibilityPolicy = UiVisibilityPolicy(),
        hudOrder: Int = 0,
        pausesGame: Boolean = false,
        content: UiScope.() -> Unit,
    ) : this(UiText.Literal(title), presentation, category, inputPolicy, visibility, hudOrder, pausesGame, content)

    /**
     * Opens on the installed client runtime's owner thread; pre-transfer rejection leaves this definition available.
     */
    public fun open(): UiSession = UiPresenters.present(this)

    /**
     * Atomically transfers contents to one runtime; application code must not call this privileged operation.
     */
    @InternalStrataRuntimeApi
    public fun transfer(): UiDefinitionPayload {
        while (true) {
            when (val current = state.load()) {
                is State.Available -> if (state.compareAndSet(current, State.Transferred)) return current.payload
                State.Transferred -> throw ScreenDefinitionUnavailableException("UI definition was already transferred.")
                State.Closed -> throw ScreenDefinitionUnavailableException("UI definition is closed.")
            }
        }
    }

    /**
     * Releases unused contents; after transfer, only the returned session owns termination.
     */
    override fun close() {
        while (true) {
            when (val current = state.load()) {
                is State.Available -> if (state.compareAndSet(current, State.Closed)) return
                State.Transferred, State.Closed -> return
            }
        }
    }

    private sealed interface State {
        class Available(
            val payload: UiDefinitionPayload,
        ) : State

        data object Transferred : State

        data object Closed : State
    }
}
