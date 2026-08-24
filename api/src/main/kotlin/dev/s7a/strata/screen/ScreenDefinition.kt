package dev.s7a.strata.screen

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot platform-neutral definition for one declarative screen.
 *
 * Construction retains but does not evaluate [content].
 * The definition owns its title, pause policy, callback, and caller capture graph until one runtime atomically transfers them or [close] releases them.
 * Transfer and close may race across threads, but exactly one operation owns the payload and no value is transferred twice.
 * Definitions have referential identity and do not define value equality.
 *
 * @param title exact unresolved title retained until transfer or close.
 * @param pausesGame whether the screen pauses its host game.
 * @param content owner-thread callback that emits exactly one root component when evaluated by a runtime.
 */
@OptIn(InternalStrataRuntimeApi::class)
public class ScreenDefinition(
    title: UiText,
    pausesGame: Boolean = false,
    content: UiScope.() -> Unit,
) : AutoCloseable {
    private val state =
        AtomicReference<State>(
            State.Available(ScreenDefinitionPayload(title, pausesGame, content)),
        )

    /**
     * Creates a definition with a literal title.
     *
     * @param title literal title retained as [UiText.Literal].
     * @param pausesGame whether the screen pauses its host game.
     * @param content owner-thread callback that emits exactly one root component when evaluated by a runtime.
     */
    public constructor(
        title: String,
        pausesGame: Boolean = false,
        content: UiScope.() -> Unit,
    ) : this(UiText.Literal(title), pausesGame, content)

    /**
     * Presents this definition through the runtime installed for the current platform.
     *
     * The call is synchronous and does not dispatch to another thread.
     * A missing runtime or a runtime thread rejection occurs before ownership transfer, so the caller may retry or [close] this definition.
     * Once a runtime transfers the definition, that runtime owns terminal cleanup even when presentation fails.
     *
     * @throws ScreenRuntimeUnavailableException when no platform runtime is installed.
     * @throws ScreenOpenThreadException when the installed runtime rejects the calling thread before transfer.
     * @throws ScreenDefinitionUnavailableException when this definition was already transferred or closed.
     * @throws Throwable when the installed runtime fails during presentation; the runtime preserves ownership and cleanup obligations after transfer.
     */
    public fun open() {
        Screens.open(this)
    }

    /**
     * Atomically transfers the complete definition payload to one runtime adapter.
     *
     * Application code must not invoke this privileged ownership operation.
     *
     * @return the uniquely transferred payload.
     * @throws ScreenDefinitionUnavailableException when this definition was transferred or closed.
     */
    @InternalStrataRuntimeApi
    public fun transfer(): ScreenDefinitionPayload {
        while (true) {
            when (val current = state.get()) {
                is State.Available -> {
                    if (state.compareAndSet(current, State.Transferred)) {
                        return current.payload
                    }
                }

                State.Transferred -> {
                    throw ScreenDefinitionUnavailableException("Screen definition was already transferred.")
                }

                State.Closed -> {
                    throw ScreenDefinitionUnavailableException("Screen definition is closed.")
                }
            }
        }
    }

    /**
     * Releases an untransferred definition and its complete callback capture graph.
     *
     * Close is thread-safe and idempotent.
     * Closing after successful runtime transfer is a no-op.
     */
    override fun close() {
        while (true) {
            when (val current = state.get()) {
                is State.Available -> if (state.compareAndSet(current, State.Closed)) return
                State.Transferred, State.Closed -> return
            }
        }
    }

    private sealed interface State {
        class Available(
            val payload: ScreenDefinitionPayload,
        ) : State

        data object Transferred : State

        data object Closed : State
    }
}
