package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.state.StateSource
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty

/**
 * Owns one retained UI description, its state declarations, and its external bindings.
 *
 * The session captures its owner thread at construction.
 * Lifecycle operations, delegate access, frame production, and pointer dispatch are confined to that thread.
 * External source callbacks only enqueue a revisioned value and always return normally.
 * The session is an internal runtime component with complete lifecycle, frame, input, and cleanup behavior.
 *
 * @param ownerDispatcher is caller-owned, always queues onto the construction thread, never runs inline, and remains serviced until cancelled generations finish.
 * @param taskFailureHandler receives non-cancellation root coroutine failures on the owner thread and selects whether the session continues or fails.
 * @param contentOwner owns and evaluates the content description until terminal failure or close releases it.
 */
@Suppress("TooManyFunctions")
internal class UiSession private constructor(
    private val ownerDispatcher: CoroutineDispatcher,
    private val taskFailureHandler: (Throwable) -> UiTaskFailureDecision,
    private val contentOwner: SessionContent,
) : AutoCloseable {
    /**
     * Creates a session that owns a content lambda.
     *
     * @param ownerDispatcher the dispatcher used by retained coroutine generations.
     * @param taskFailureHandler the typed task-failure policy.
     * @param content the owner-thread content evaluator.
     */
    internal constructor(
        ownerDispatcher: CoroutineDispatcher,
        taskFailureHandler: (Throwable) -> UiTaskFailureDecision = { UiTaskFailureDecision.FailSession },
        content: () -> Element,
    ) : this(ownerDispatcher, taskFailureHandler, SessionContent(content))

    /**
     * Creates a session from an existing content owner.
     *
     * The supplied owner follows the same terminal-release contract as content created by the other constructor.
     *
     * @param ownerDispatcher the dispatcher used by retained coroutine generations.
     * @param contentOwner the owner of the content evaluator.
     * @param taskFailureHandler the typed task-failure policy.
     */
    internal constructor(
        ownerDispatcher: CoroutineDispatcher,
        contentOwner: SessionContent,
        taskFailureHandler: (Throwable) -> UiTaskFailureDecision = { UiTaskFailureDecision.FailSession },
    ) : this(ownerDispatcher, taskFailureHandler, contentOwner)

    private val threadGuard: ThreadGuard = ThreadGuard.currentThread()
    private val bindings: MutableList<UiSessionBinding<*>> = ArrayList()
    private val screenScopeFacade = SessionScreenScope()

    @Volatile
    private var screenScopeContext: CoroutineContext = inactiveScopeContext()

    @Volatile
    private var currentGeneration: SessionGeneration? = null

    @Volatile
    private var currentState: UiSessionState = UiSessionState.Created
    private var stateMutationActive: Boolean = false
    private var operationKind: SessionOperation? = null
    private var establishingBinding: Boolean = false
    private var evaluatingContent: Boolean = false
    private var dirty: Boolean = true
    private var tree: UiTree? = null
    private var frameAvailable: Boolean = false
    private var cachedFrame: UiFrame? = null
    private var cachedFrameConstraints: Constraints? = null
    private var cachedTreeRevision: Long = 0L

    /**
     * Provides one stable coroutine scope whose job is replaced for every attachment.
     *
     * The scope context is active only while the session is attached.
     * Launches made while the session is created, detached, failed, or closed are cancelled before their body starts.
     * The scope may be used from a worker coroutine; its generation dispatcher resumes work on the owner thread.
     */
    internal val screenScope: CoroutineScope
        get() = screenScopeFacade

    /**
     * The lifecycle state read by synchronous runtime tests and integration code.
     *
     * The value is owner-thread confined.
     * A failed session records its terminal failure until [close] transitions it to closed.
     */
    internal val lifecycleState: UiSessionState
        get() {
            threadGuard.check()
            checkGenerationForLifecycleAccess()
            return currentState
        }

    /**
     * Declares local mutable state for use by the content description.
     *
     * Declaration is legal only before the first lifecycle transition and on the owner thread.
     * The returned delegate may be read or written while the session is created, attached, or detached.
     * A write that changes the value marks the next frame dirty.
     *
     * @param initial the initial local value.
     * @return a read/write property delegate owned by this session.
     * @throws IllegalStateException when declaration occurs after creation or during content.
     */
    internal fun <T> state(initial: T): ReadWriteProperty<Any?, T> {
        checkDeclaration()
        return UiSessionLocalState(
            initial,
            ::checkReadable,
            ::checkWritable,
            ::checkWritableAfterEquality,
            ::markDirty,
            ::beginStateMutation,
            ::endStateMutation,
        )
    }

    /**
     * Declares a read-only revisioned source binding.
     *
     * Subscription is established during declaration after the binding receiver has been installed.
     * This permits a source callback to race or precede the return from subscribe.
     * The initial snapshot is committed by revision, while newer callback snapshots remain pending until a frame.
     * The returned delegate can be read while created, attached, or detached, but declaration is only legal while created.
     *
     * @param source the externally owned revisioned source.
     * @return a read-only property delegate owned by this session.
     * @throws Throwable when source subscription establishment fails.
     * @throws IllegalStateException when declaration occurs after creation or during content.
     */
    internal fun <T> bind(source: StateSource<T>): ReadOnlyProperty<Any?, T> {
        checkDeclaration()
        beginOperation(SessionOperation.Bind)
        establishingBinding = true
        val binding = UiSessionBinding<T>(::checkReadable, ::beginStateMutation, ::endStateMutation)
        bindings.add(binding)
        try {
            return runCatching {
                val subscription = source.subscribe(binding::enqueue)
                binding.install(subscription)
                binding.commitInitial(subscription.initialSnapshot)
                binding
            }.getOrElse { failure ->
                bindings.remove(binding)
                val failures = FailureAccumulator(failure)
                closeBinding(binding, failures)
                failures.throwFirst()
            }
        } finally {
            establishingBinding = false
            endOperation()
        }
    }

    /**
     * Attaches the session and builds its retained description when dirty.
     *
     * A created or detached session becomes attached.
     * A dirty description is evaluated once and reconciled once; the retained tree remains through detachment.
     * Invalid transitions throw without changing the session state.
     * Content and tree failures poison the session and close all source subscriptions and retained ownership.
     */
    internal fun attach() {
        beginOperation(SessionOperation.Attach)
        try {
            check(currentState === UiSessionState.Created || currentState === UiSessionState.Detached) {
                "A session can attach only from Created or Detached."
            }
            runCatching {
                if (tree == null) {
                    tree = UiTree()
                }
                val generation = createGeneration()
                currentState = UiSessionState.Attached
                currentGeneration = generation
                screenScopeContext = generation.context
                applyBindingCutoff()
                if (dirty) {
                    rebuildContent()
                }
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Detaches the session while retaining its tree, state values, and source subscriptions.
     *
     * Detachment is owner-thread confined and legal only from the attached state.
     * A previously committed frame clears every active pointer-hover transition before the tree is retained.
     * The immutable frame cache is released so reattachment always commits layout-dependent input state again.
     * Pending source values remain queued and are applied at the next frame after reattachment.
     * A hover callback failure poisons the session and closes retained ownership while preserving the exact failure as primary.
     */
    internal fun detach() {
        beginOperation(SessionOperation.Detach)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can detach only from Attached."
            }
            runCatching {
                if (frameAvailable) {
                    checkNotNull(tree) { "An attached session has no retained tree." }.clearInputState()
                }
                clearCachedFrame()
                currentState = UiSessionState.Detached
                frameAvailable = false
                retireGeneration()
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Produces one immutable frame from the attached session.
     *
     * Pending source snapshots are atomically cut off and applied before content is rebuilt.
     * A callback arriving after the cutoff remains pending for the following frame.
     * Dirty content runs at most once per frame and always produces a fresh immutable snapshot after measure, layout, paint, and semantics complete.
     * A frame that did not rebuild content reuses the previous immutable snapshot only while constraints and the whole-tree revision remain unchanged.
     * Invalidation raised by a pipeline callback prevents that new snapshot from being cached, leaving its work pending for the following frame.
     * A failure poisons the session and closes all bindings and the tree.
     *
     * @param constraints the root measurement constraints.
     * @return the immutable measured size, drawing commands, and semantics snapshot.
     * @throws Throwable when content, retained reconciliation, or any tree pipeline fails.
     * @throws IllegalStateException when called from a wrong lifecycle state, wrong thread, or reentrant operation.
     */
    internal fun frame(constraints: Constraints): UiFrame {
        beginOperation(SessionOperation.Frame)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can produce a frame only while Attached."
            }
            return runCatching {
                applyBindingCutoff()
                var contentRebuilt = false
                if (dirty) {
                    rebuildContent()
                    contentRebuilt = true
                }
                val retainedTree = checkNotNull(tree) { "An attached session has no retained tree." }
                val revision = retainedTree.currentRevision()
                if (contentRebuilt.not()) {
                    val retainedFrame = cachedFrame
                    if (
                        retainedFrame != null &&
                        cachedFrameConstraints == constraints &&
                        cachedTreeRevision == revision
                    ) {
                        frameAvailable = true
                        return@runCatching retainedFrame
                    }
                }
                clearCachedFrame()
                frameAvailable = false
                val size = retainedTree.measure(constraints)
                retainedTree.layout()
                val draw = retainedTree.paint()
                val semantics = retainedTree.semantics()
                val frame = UiFrame(size, draw, semantics)
                frameAvailable = true
                if (retainedTree.currentRevision() == revision) {
                    cachedFrameConstraints = constraints
                    cachedTreeRevision = revision
                    cachedFrame = frame
                }
                frame
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Dispatches one pointer event through the most recently committed attached tree.
     *
     * Before the first successful frame, and while a newly reconciled tree has not completed a frame, input is consistently ignored.
     * Pending source values therefore never affect input before their frame is committed.
     * A pointer pipeline failure poisons the session.
     *
     * @param event the event in session coordinates.
     * @return the retained tree's input result, or [InputResult.Ignored] without a committed frame.
     * @throws Throwable when retained pointer dispatch fails.
     */
    internal fun dispatchPointer(event: PointerEvent): InputResult {
        beginOperation(SessionOperation.Input)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can dispatch pointer input only while Attached."
            }
            if (frameAvailable.not()) {
                return InputResult.Ignored
            }
            return runCatching {
                checkNotNull(tree) { "An attached session has no retained tree." }.dispatchPointer(event)
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Dispatches one keyboard event through the most recently committed attached tree.
     *
     * @param event immutable keyboard event.
     * @return focused input result, or [InputResult.Ignored] without a committed frame.
     * @throws Throwable when focused keyboard dispatch fails.
     */
    internal fun dispatchKeyboard(event: KeyboardEvent): InputResult = dispatchFocusedInput { retainedTree -> retainedTree.dispatchKeyboard(event) }

    /**
     * Dispatches one text-input event through the most recently committed attached tree.
     *
     * @param event immutable committed-character or preedit event.
     * @return focused input result, or [InputResult.Ignored] without a committed frame.
     * @throws Throwable when focused text dispatch fails.
     */
    internal fun dispatchTextInput(event: TextInputEvent): InputResult = dispatchFocusedInput { retainedTree -> retainedTree.dispatchTextInput(event) }

    private fun dispatchFocusedInput(dispatch: (UiTree) -> InputResult): InputResult {
        beginOperation(SessionOperation.Input)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can dispatch focused input only while Attached."
            }
            if (frameAvailable.not()) {
                return InputResult.Ignored
            }
            return runCatching {
                dispatch(checkNotNull(tree) { "An attached session has no retained tree." })
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Closes all source subscriptions and retained ownership exactly once.
     *
     * The state becomes closed before cleanup starts.
     * Cleanup continues after failures in binding declaration order and then closes the retained tree.
     * The first cleanup failure is rethrown unchanged with later distinct failures suppressed.
     * Closing a failed session only records the terminal Closed state because failure cleanup already ran.
     * Closing after a completed close, or recursively from that close's cleanup, is an owner-thread no-op.
     */
    override fun close() {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        if (
            currentState === UiSessionState.Closed &&
            (operationKind == null || operationKind === SessionOperation.Close)
        ) {
            return
        }
        checkGenerationForLifecycleAccess()
        check(operationKind == null) { "A session operation is already active." }
        if (currentState is UiSessionState.Failed) {
            currentState = UiSessionState.Closed
            clearCachedFrame()
            releaseContent()
            return
        }
        operationKind = SessionOperation.Close
        try {
            currentState = UiSessionState.Closed
            clearCachedFrame()
            releaseContent()
            retireGeneration()
            val failures = cleanupResources()
            failures?.let { failure -> throw failure }
        } finally {
            endOperation()
        }
    }

    private fun rebuildContent() {
        val retainedTree = checkNotNull(tree) { "A session must have a tree before rebuilding." }
        clearCachedFrame()
        evaluatingContent = true
        val description =
            try {
                contentOwner.evaluate()
            } finally {
                evaluatingContent = false
            }
        dirty = false
        retainedTree.update(description)
        frameAvailable = false
    }

    private fun applyBindingCutoff() {
        bindings.forEach { binding ->
            if (binding.applyPending()) {
                dirty = true
            }
        }
    }

    private fun checkDeclaration() {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        check(operationKind == null) { "A session operation is already active." }
        check(evaluatingContent.not()) { "State declarations are not allowed during content evaluation." }
        check(currentState === UiSessionState.Created) { "State declarations are allowed only while Created." }
    }

    private fun checkReadable() {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        check(establishingBinding.not()) { "State access is not allowed during binding establishment." }
        checkGenerationForStateAccess()
    }

    private fun checkWritable() {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        checkWritablePhase()
    }

    private fun checkWritableAfterEquality() {
        threadGuard.check()
        checkWritablePhase()
    }

    private fun checkWritablePhase() {
        check(establishingBinding.not()) { "State access is not allowed during binding establishment." }
        check(evaluatingContent.not()) { "State mutation is not allowed during content evaluation." }
        val operation = operationKind
        check(operation == null || operation === SessionOperation.Input || operation === SessionOperation.TaskFailure) {
            "State mutation is not allowed during a session operation."
        }
        checkGenerationForStateAccess()
    }

    private fun markDirty() {
        dirty = true
    }

    private fun beginOperation(kind: SessionOperation) {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        checkGenerationForLifecycleAccess()
        check(operationKind == null) { "A session operation is already active." }
        operationKind = kind
    }

    private fun endOperation() {
        operationKind = null
    }

    private fun beginStateMutation() {
        check(stateMutationActive.not()) { "Session mutation is already active." }
        stateMutationActive = true
    }

    private fun endStateMutation() {
        stateMutationActive = false
    }

    private fun fail(primary: Throwable): Nothing {
        currentState = UiSessionState.Failed(primary)
        clearCachedFrame()
        releaseContent()
        frameAvailable = false
        retireGeneration()
        val failures = FailureAccumulator(primary)
        failures.addOptional(cleanupResources())
        failures.throwFirst()
    }

    private fun cleanupResources(): Throwable? {
        val failures = FailureAccumulator()
        clearCachedFrame()
        val ownedBindings = bindings.toList()
        bindings.clear()
        val retainedTree = tree
        tree = null
        ownedBindings.forEach { binding ->
            closeBinding(binding, failures)
        }
        if (retainedTree != null) {
            failures.capture { retainedTree.close() }
        }
        return failures.first
    }

    private fun clearCachedFrame() {
        cachedFrame = null
        cachedFrameConstraints = null
        cachedTreeRevision = 0L
    }

    private fun releaseContent() {
        contentOwner.release()
    }

    private fun closeBinding(
        binding: UiSessionBinding<*>,
        failures: FailureAccumulator,
    ) {
        failures.capture { binding.disable() }
        val closeFailure =
            runCatching { binding.closeSubscription() }
                .onFailure(failures::add)
                .getOrNull()
        failures.addOptional(closeFailure)
    }

    private fun checkGenerationForLifecycleAccess() {
        val generation = SessionGenerationContext.current() ?: return
        check(
            generation.active && generation === currentGeneration?.token && currentState === UiSessionState.Attached,
        ) {
            "The coroutine generation is no longer active."
        }
    }

    private fun checkGenerationForStateAccess() {
        val generation = SessionGenerationContext.current()
        if (generation == null) {
            check(
                currentState === UiSessionState.Created ||
                    currentState === UiSessionState.Attached ||
                    currentState === UiSessionState.Detached,
            ) { "State cannot be read after session failure or close." }
            return
        }
        check(
            generation.active && generation === currentGeneration?.token && currentState === UiSessionState.Attached,
        ) { "The coroutine generation cannot access session state." }
    }

    private fun createGeneration(): SessionGeneration {
        val job = SupervisorJob()
        val token = SessionGenerationToken()
        val dispatcher = GenerationDispatcher(ownerDispatcher, threadGuard)
        val context =
            job +
                dispatcher +
                SessionGenerationContext(token) +
                GenerationExceptionHandler(token)
        return SessionGeneration(token, job, context)
    }

    private fun retireGeneration() {
        val generation = currentGeneration ?: return
        generation.token.active = false
        currentGeneration = null
        screenScopeContext = inactiveScopeContext()
        generation.job.cancel()
    }

    private fun inactiveScopeContext(): CoroutineContext {
        val job = SupervisorJob()
        job.cancel()
        return job
    }

    private fun currentScreenScopeContext(): CoroutineContext {
        if (threadGuard.isOwnerThread()) {
            check(evaluatingContent.not()) { "The screen scope is unavailable during content evaluation." }
        }
        val generation = SessionGenerationContext.current()
        if (generation != null) {
            check(
                generation.active && generation === currentGeneration?.token && currentState === UiSessionState.Attached,
            ) { "The coroutine generation is no longer active." }
        }
        return screenScopeContext
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleTaskFailure(
        generation: SessionGenerationToken,
        failure: Throwable,
    ) {
        if (failure is CancellationException) {
            return
        }
        beginTaskFailureDelivery()
        try {
            val wasCurrent = isCurrentGeneration(generation)
            val decision =
                try {
                    taskFailureHandler(failure)
                } catch (handlerFailure: Throwable) {
                    if (wasCurrent && isCurrentGeneration(generation)) {
                        failTaskDuringDelivery(failure, handlerFailure)
                    }
                    val failures = FailureAccumulator(failure)
                    failures.addOptional(handlerFailure)
                    failures.throwFirst()
                }
            if (decision === UiTaskFailureDecision.Continue) {
                return
            }
            if (wasCurrent.not() || isCurrentGeneration(generation).not()) {
                throw failure
            }
            failTaskDuringDelivery(failure)
        } finally {
            endOperation()
        }
    }

    private fun beginTaskFailureDelivery() {
        threadGuard.check()
        check(stateMutationActive.not()) { "Session mutation is already active." }
        check(operationKind == null) { "A session operation is already active." }
        operationKind = SessionOperation.TaskFailure
    }

    private fun failTaskDuringDelivery(
        failure: Throwable,
        handlerFailure: Throwable? = null,
    ): Nothing {
        check(operationKind === SessionOperation.TaskFailure) { "Task failure delivery is not active." }
        currentState = UiSessionState.Failed(failure)
        releaseContent()
        frameAvailable = false
        retireGeneration()
        val failures = FailureAccumulator(failure)
        failures.addOptional(handlerFailure)
        failures.addOptional(cleanupResources())
        failures.throwFirst()
    }

    private fun isCurrentGeneration(generation: SessionGenerationToken): Boolean =
        generation.active &&
            (generation === currentGeneration?.token && currentState === UiSessionState.Attached)

    private fun runWithGeneration(
        generation: SessionGenerationToken,
        action: () -> Unit,
    ) {
        val contextElement = SessionGenerationContext(generation)
        val oldGeneration = contextElement.updateThreadContext(EmptyCoroutineContext)
        try {
            action()
        } finally {
            contextElement.restoreThreadContext(EmptyCoroutineContext, oldGeneration)
        }
    }

    private enum class SessionOperation {
        Attach,
        Detach,
        Frame,
        Input,
        Close,
        Bind,
        TaskFailure,
    }

    private inner class SessionScreenScope : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = currentScreenScopeContext()
    }

    private class SessionGenerationToken {
        @Volatile
        var active: Boolean = true
    }

    private class SessionGeneration(
        val token: SessionGenerationToken,
        val job: CompletableJob,
        val context: CoroutineContext,
    )

    private class GenerationDispatcher(
        private val ownerDispatcher: CoroutineDispatcher,
        private val threadGuard: ThreadGuard,
    ) : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val dispatchThread = Thread.currentThread()
            val returned = AtomicBoolean(false)
            val violation = AtomicReference<Throwable?>(null)
            ownerDispatcher.dispatch(context) {
                if (returned.get().not() && Thread.currentThread() === dispatchThread) {
                    val failure = IllegalStateException("The owner dispatcher must queue before execution.")
                    violation.set(failure)
                    throw failure
                }
                threadGuard.check()
                block.run()
            }
            returned.set(true)
            violation.get()?.let { failure -> throw failure }
        }
    }

    private inner class GenerationExceptionHandler(
        private val generation: SessionGenerationToken,
    ) : AbstractCoroutineContextElement(CoroutineExceptionHandler),
        CoroutineExceptionHandler {
        override fun handleException(
            context: CoroutineContext,
            exception: Throwable,
        ) {
            dispatchOwner(context) {
                runWithGeneration(generation) { handleTaskFailure(generation, exception) }
            }
        }
    }

    private fun dispatchOwner(
        context: CoroutineContext,
        block: () -> Unit,
    ) {
        val dispatchThread = Thread.currentThread()
        val returned = AtomicBoolean(false)
        val violation = AtomicReference<Throwable?>(null)
        ownerDispatcher.dispatch(context) {
            if (returned.get().not() && Thread.currentThread() === dispatchThread) {
                val failure = IllegalStateException("The owner dispatcher must queue before execution.")
                violation.set(failure)
                throw failure
            }
            threadGuard.check()
            block()
        }
        returned.set(true)
        violation.get()?.let { failure -> throw failure }
    }

    private class SessionGenerationContext(
        private val generation: SessionGenerationToken,
    ) : AbstractCoroutineContextElement(Key),
        ThreadContextElement<SessionGenerationToken?> {
        override fun updateThreadContext(context: CoroutineContext): SessionGenerationToken? {
            val previous = threadGeneration.get()
            threadGeneration.set(generation)
            return previous
        }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: SessionGenerationToken?,
        ) {
            if (oldState == null) {
                threadGeneration.remove()
            } else {
                threadGeneration.set(oldState)
            }
        }

        companion object {
            val threadGeneration: ThreadLocal<SessionGenerationToken?> = ThreadLocal()

            object Key : CoroutineContext.Key<SessionGenerationContext>

            fun current(): SessionGenerationToken? = threadGeneration.get()
        }
    }
}
