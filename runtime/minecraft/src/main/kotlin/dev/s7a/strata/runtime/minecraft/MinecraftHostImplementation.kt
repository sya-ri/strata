package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owns one-shot definition transfer and the private owner-thread host implementation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftHostImplementation {
    /**
     * Consumes a definition and creates one independent host from its complete profile.
     *
     * @param definition available one-shot definition.
     * @param profile complete profile created by this runtime.
     * @return an owner-thread host with transferred metadata and content.
     * @throws IllegalStateException when [definition] is unavailable.
     */
    @JvmSynthetic
    fun create(
        definition: MinecraftScreenDefinition,
        profile: MinecraftUiProfile,
    ): MinecraftUiHost {
        val transferred = MinecraftDefinitionImplementation.take(definition)
        val evaluator = MinecraftProfileImplementation.createEvaluator(profile, transferred.content)
        val session = createRuntimeUiSession(evaluator)
        return Host.create(session, evaluator, transferred.title, transferred.pausesGame)
    }

    private enum class State {
        Created,
        Attached,
        Detached,
        Failed,
        Closed,
    }

    private enum class Operation {
        Attach,
        Detach,
        Frame,
        Input,
        Close,
    }

    private class Metadata(
        @Suppress("unused")
        val title: UiText,
        @Suppress("unused")
        val pausesGame: Boolean,
    )

    // Why: one host owns the complete state transition surface and delegates each public operation through the same failure boundary.
    @Suppress("TooManyFunctions")
    private class Host private constructor(
        private val session: RuntimeUiSession,
        initialEvaluator: () -> Element,
        title: UiText,
        pausesGame: Boolean,
    ) : MinecraftUiHost {
        private val ownerThread = Thread.currentThread()
        private var evaluator: (() -> Element)? = initialEvaluator
        private var metadata: Metadata? = Metadata(title, pausesGame)
        private var state = State.Created
        private var operation: Operation? = null

        override val title: UiText
            get() {
                checkReadable()
                return checkNotNull(metadata).title
            }

        override val pausesGame: Boolean
            get() {
                checkReadable()
                return checkNotNull(metadata).pausesGame
            }

        override fun attach() {
            checkOwner()
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Created || state == State.Detached) { "Minecraft UI host cannot attach from its current state." }
            operation = Operation.Attach
            try {
                runCatching { session.attach() }.getOrElse { failure -> fail(failure) }
                state = State.Attached
            } finally {
                releaseEvaluator()
                operation = null
            }
        }

        override fun detach() {
            checkOwner()
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before detach." }
            operation = Operation.Detach
            try {
                runCatching {
                    session.detach()
                    state = State.Detached
                }.getOrElse { failure -> fail(failure) }
            } finally {
                operation = null
            }
        }

        override fun frame(viewport: IntSize): RuntimeUiFrame {
            checkOwner()
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before frame." }
            operation = Operation.Frame
            return try {
                runCatching { session.frame(Constraints.fixed(viewport.width, viewport.height)) }
                    .getOrElse { failure -> fail(failure) }
            } finally {
                operation = null
            }
        }

        override fun dispatchPointer(event: PointerEvent): InputResult {
            checkOwner()
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before pointer input." }
            operation = Operation.Input
            return try {
                runCatching { session.dispatchPointer(event) }.getOrElse { failure -> fail(failure) }
            } finally {
                operation = null
            }
        }

        override fun dispatchKeyboard(event: KeyboardEvent): InputResult = runInput { session.dispatchKeyboard(event) }

        override fun dispatchTextInput(event: TextInputEvent): InputResult = runInput { session.dispatchTextInput(event) }

        private fun runInput(operation: () -> InputResult): InputResult {
            checkOwner()
            check(this.operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before focused input." }
            this.operation = Operation.Input
            try {
                return runCatching(operation).getOrElse { failure -> fail(failure) }
            } finally {
                this.operation = null
            }
        }

        override fun close() {
            checkOwner()
            if (state == State.Closed && (operation == null || operation == Operation.Close)) return
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            operation = Operation.Close
            state = State.Closed
            metadata = null
            try {
                session.close()
            } finally {
                releaseEvaluator()
                operation = null
            }
        }

        private fun checkOwner() {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI host requires its owner thread." }
        }

        private fun checkReadable() {
            checkOwner()
            check(operation == null) { "Minecraft UI host metadata reads are non-reentrant." }
            check(state == State.Created || state == State.Attached || state == State.Detached) {
                "Minecraft UI host metadata is unavailable after terminal failure or close."
            }
        }

        private fun fail(primary: Throwable): Nothing {
            state = State.Failed
            metadata = null
            runCatching { session.close() }.exceptionOrNull()?.let { cleanup -> addSuppressed(primary, cleanup) }
            releaseEvaluator()
            operation = null
            throw primary
        }

        private fun releaseEvaluator() {
            val retained = evaluator ?: return
            evaluator = null
            MinecraftProfileImplementation.releaseEvaluator(retained)
        }

        private fun addSuppressed(
            primary: Throwable,
            secondary: Throwable,
        ) {
            if (primary === secondary) return
            if (reaches(primary, secondary) || reaches(secondary, primary)) return
            primary.addSuppressed(secondary)
        }

        private fun reaches(
            start: Throwable,
            target: Throwable,
        ): Boolean {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val pending = ArrayDeque<Throwable>()
            pending.addLast(start)
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                if (current === target) return true
                if (visited.add(current)) {
                    current.cause?.let(pending::addLast)
                    for (suppressed in current.suppressed) pending.addLast(suppressed)
                }
            }
            return false
        }

        companion object {
            /**
             * Creates the private host implementation without a public constructor.
             *
             * @param session independently owned core runtime session.
             * @param evaluator one-shot content evaluator released with the host.
             * @param title exact unresolved transferred title.
             * @param pausesGame whether the transferred screen pauses the game.
             * @return a new owner-thread host.
             */
            @JvmSynthetic
            internal fun create(
                session: RuntimeUiSession,
                evaluator: () -> Element,
                title: UiText,
                pausesGame: Boolean,
            ): Host = Host(session, evaluator, title, pausesGame)
        }
    }
}
