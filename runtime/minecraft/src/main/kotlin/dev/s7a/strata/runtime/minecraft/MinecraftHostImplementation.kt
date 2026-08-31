package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.screen.ScreenDefinition
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
     * @param platform optional version services transferred to the host.
     * @param fontBackend factory borrowed to open a distinct engine for a resource-font profile; omitted for compatibility glyphs.
     * @return an owner-thread host with transferred metadata and content.
     * @throws IllegalStateException when [definition] is unavailable.
     * @throws Throwable when font initialization fails before definition transfer or later host construction fails; opened fonts are released and [platform] remains caller-owned.
     */
    @JvmSynthetic
    fun create(
        definition: ScreenDefinition,
        profile: MinecraftUiProfile,
        platform: MinecraftUiPlatform? = null,
        fontBackend: MinecraftFontBackendFactory? = null,
    ): MinecraftUiHost {
        val textRenderer = MinecraftProfileImplementation.createTextRenderer(profile, fontBackend)
        return runCatching {
            val transferred = definition.transfer()
            val evaluator = MinecraftProfileImplementation.createEvaluator(profile, transferred.content, platform, textRenderer)
            val session = createRuntimeUiSession(evaluator)
            Host.create(session, evaluator, platform, textRenderer, transferred.title, transferred.pausesGame)
        }.getOrElse { failure ->
            runCatching { textRenderer.close() }.exceptionOrNull()?.let { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
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
        initialPlatform: MinecraftUiPlatform?,
        initialTextRenderer: MinecraftTextRenderer,
        title: UiText,
        pausesGame: Boolean,
    ) : MinecraftUiHost {
        private val ownerThread = Thread.currentThread()
        private var evaluator: (() -> Element)? = initialEvaluator
        private var resourceEvaluator: (() -> Element)? = initialEvaluator
        private var platform: MinecraftUiPlatform? = initialPlatform
        private var textRenderer: MinecraftTextRenderer? = initialTextRenderer
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

        override val textInputFocus: RuntimeTextInputFocus?
            get() {
                checkReadable()
                return session.textInputFocus
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

        override fun frame(viewport: IntSize): RuntimeUiFrame = runFrame(viewport, null)

        override fun frame(
            viewport: IntSize,
            time: FrameTime,
        ): RuntimeUiFrame = runFrame(viewport, time)

        private fun runFrame(
            viewport: IntSize,
            time: FrameTime?,
        ): RuntimeUiFrame {
            checkOwner()
            check(operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before frame." }
            operation = Operation.Frame
            return try {
                runCatching {
                    platform?.refresh()
                    val constraints = Constraints.fixed(viewport.width, viewport.height)
                    if (time == null) session.frame(constraints) else session.frame(constraints, time)
                }.getOrElse { failure -> fail(failure) }
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

        override fun resetInputState() {
            runInput(session::resetInputState)
        }

        private fun <T> runInput(operation: () -> T): T {
            checkOwner()
            check(this.operation == null) { "Minecraft UI host operations are non-reentrant." }
            check(state == State.Attached) { "Minecraft UI host must be attached before input." }
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
            val sessionFailure = runCatching { session.close() }.exceptionOrNull()
            val evaluatorFailure = runCatching { releaseEvaluator() }.exceptionOrNull()
            val resourceFailure = runCatching { releaseEvaluatorResources() }.exceptionOrNull()
            val fontFailure = runCatching { releaseTextRenderer() }.exceptionOrNull()
            val platformFailure = runCatching { releasePlatform() }.exceptionOrNull()
            val failure = sessionFailure ?: evaluatorFailure ?: resourceFailure ?: fontFailure ?: platformFailure
            if (failure != null) {
                evaluatorFailure?.let { addSuppressed(failure, it) }
                resourceFailure?.let { addSuppressed(failure, it) }
                fontFailure?.let { addSuppressed(failure, it) }
                platformFailure?.let { addSuppressed(failure, it) }
            }
            operation = null
            failure?.let { throw it }
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
            runCatching { releaseEvaluator() }.exceptionOrNull()?.let { cleanup -> addSuppressed(primary, cleanup) }
            runCatching { releaseEvaluatorResources() }.exceptionOrNull()?.let { cleanup -> addSuppressed(primary, cleanup) }
            runCatching { releaseTextRenderer() }.exceptionOrNull()?.let { cleanup -> addSuppressed(primary, cleanup) }
            runCatching { releasePlatform() }.exceptionOrNull()?.let { cleanup -> addSuppressed(primary, cleanup) }
            operation = null
            throw primary
        }

        private fun releaseEvaluator() {
            val retained = evaluator ?: return
            evaluator = null
            MinecraftProfileImplementation.releaseEvaluator(retained)
        }

        private fun releaseEvaluatorResources() {
            val retained = resourceEvaluator ?: return
            resourceEvaluator = null
            MinecraftProfileImplementation.releaseEvaluatorResources(retained)
        }

        private fun releasePlatform() {
            val retained = platform ?: return
            platform = null
            retained.close()
        }

        private fun releaseTextRenderer() {
            val retained = textRenderer ?: return
            textRenderer = null
            retained.close()
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
             * @param platform optional version services owned until terminal close.
             * @param textRenderer independently owned text service closed after the retained session.
             * @param title exact unresolved transferred title.
             * @param pausesGame whether the transferred screen pauses the game.
             * @return a new owner-thread host.
             */
            @JvmSynthetic
            internal fun create(
                session: RuntimeUiSession,
                evaluator: () -> Element,
                platform: MinecraftUiPlatform?,
                textRenderer: MinecraftTextRenderer,
                title: UiText,
                pausesGame: Boolean,
            ): Host = Host(session, evaluator, platform, textRenderer, title, pausesGame)
        }
    }
}
