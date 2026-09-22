package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.runtime.platform.currentThread
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.StateSource
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
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
 * @param content the owner-thread content evaluator, released before terminal cleanup callbacks.
 */
@Suppress("TooManyFunctions", "LargeClass") // One owner enforces frame, input, coroutine, and diagnostic operation boundaries.
@OptIn(InternalStrataRuntimeApi::class, ExperimentalAtomicApi::class)
internal class UiSession(
    private val ownerDispatcher: CoroutineDispatcher,
    private val taskFailureHandler: (Throwable) -> UiTaskFailureDecision = { UiTaskFailureDecision.FailSession },
    content: () -> Element,
) : AutoCloseable {
    private var retainedContent: (() -> Element)? = content
    private val threadGuard: ThreadGuard = ThreadGuard()
    private val stateObservation =
        StateObservation(
            beforeMutation = {
                checkWritable()
                beginStateMutation()
            },
            afterMutation = ::endStateMutation,
            invalidated = ::markDirty,
            validateMutation = ::checkWritable,
        )
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

    /**
     * Starts bounded diagnostics without changing content or requesting a frame.
     */
    internal fun startRenderMonitoring(): UiRenderMonitor {
        threadGuard.check()
        check(operationKind == null && stateMutationActive.not()) { "Monitoring requires an idle session." }
        return checkNotNull(tree) { "Attach the session before starting monitoring." }.startMonitoring {
            check(operationKind == null && stateMutationActive.not()) { "Monitoring requires an idle session." }
        }
    }

    private var frameAvailable: Boolean = false
    private var committedFrameConstraints: Constraints? = null
    private var cachedFrame: RuntimeUiFrame? = null
    private var cachedFrameConstraints: Constraints? = null
    private var cachedTreeRevision: Long = 0L

    /**
     * Detached editable-focus identity from the latest committed attached frame.
     *
     * Created and detached sessions return null, as do attached sessions before a successful frame.
     * Reads are confined to the owner thread outside session operations and reject terminal states without changing the session.
     */
    internal val textInputFocus: RuntimeTextInputFocus?
        get() {
            checkReadable()
            check(operationKind == null) { "A session operation is already active." }
            return if (currentState === UiSessionState.Attached && frameAvailable) tree?.currentTextInputFocus() else null
        }

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
                    tree =
                        UiTree().also {
                            it.monitoring.operation = UiRenderOperation.Attach
                            it.stateObservation = stateObservation
                        }
                }
                val generation = createGeneration()
                currentState = UiSessionState.Attached
                currentGeneration = generation
                screenScopeContext = generation.context
                applyBindingCutoff()
                if (dirty) {
                    rebuildContent()
                }
                checkNotNull(tree) { "An attached session has no retained tree." }.sessionAttached()
                checkNotNull(tree) { "An attached session has no retained tree." }.finishFrameState()
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    /**
     * Detaches the session while retaining its tree, state values, and session source subscriptions.
     *
     * Detachment is owner-thread confined and legal only from the attached state.
     * A previously committed frame cancels pointer capture and clears every active hover and focus transition before the tree is retained.
     * The immutable frame cache is released so reattachment always commits layout-dependent input state again.
     * Pending source values remain queued and are applied at the next frame after reattachment.
     * Nodes with session-attachment resources suspend their observations and reacquire them on reattachment.
     * An input or resource callback failure poisons the session and closes retained ownership while preserving the exact failure as primary.
     */
    internal fun detach() {
        beginOperation(SessionOperation.Detach)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can detach only from Attached."
            }
            runCatching {
                val retainedTree = checkNotNull(tree) { "An attached session has no retained tree." }
                val failures = FailureAccumulator()
                if (frameAvailable) {
                    failures.capture(retainedTree::clearInputState)
                }
                if (retainedTree.state === TreeState.Active) failures.capture(retainedTree::sessionDetached)
                failures.throwIfPresent()
                clearCachedFrame()
                currentState = UiSessionState.Detached
                frameAvailable = false
                committedFrameConstraints = null
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
    internal fun frame(constraints: Constraints): RuntimeUiFrame = frame(constraints, null)

    /**
     * Commits declarations at the ordinary frame cutoff without running any presentation phase.
     * Projection is read-only and follows the same failure and cleanup contract as a rendered frame.
     */
    internal fun <T> projectDeclarations(project: (RuntimeDeclaration) -> T): T {
        beginOperation(SessionOperation.Frame)
        try {
            check(currentState === UiSessionState.Attached) { "Declaration projection requires an attached session." }
            return runCatching {
                applyBindingCutoff()
                if (dirty) rebuildContent()
                val retainedTree = checkNotNull(tree)
                retainedTree.refreshObservedContent()
                val result = retainedTree.projectDeclarations(project)
                retainedTree.finishFrameState()
                result
            }.getOrElse(::fail)
        } finally {
            endOperation()
        }
    }

    /**
     * Executes an already authenticated remote action through the shared owner-thread input boundary.
     */
    internal fun dispatchAction(action: () -> Unit) {
        beginOperation(SessionOperation.Input)
        try {
            check(currentState === UiSessionState.Attached) { "Remote actions require an attached session." }
            runCatching(action).getOrElse(::fail)
        } finally {
            endOperation()
        }
    }

    /**
     * Produces one immutable frame after notifying time-aware retained nodes with [time].
     *
     * @param constraints root measurement constraints.
     * @param time optional explicit host timestamp; null preserves untimed cache behavior.
     * @return immutable frame snapshot.
     */
    internal fun frame(
        constraints: Constraints,
        time: FrameTime?,
    ): RuntimeUiFrame {
        beginOperation(SessionOperation.Frame)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can produce a frame only while Attached."
            }
            tree?.monitoring?.record(UiRenderMetric.FrameAttempt)
            return runCatching {
                applyBindingCutoff()
                var contentRebuilt = false
                if (dirty) {
                    rebuildContent()
                    contentRebuilt = true
                }
                val retainedTree = checkNotNull(tree) { "An attached session has no retained tree." }
                if (time != null) retainedTree.advanceFrame(time)
                retainedTree.refreshObservedContent()
                val revision = retainedTree.currentRevision()
                if (contentRebuilt.not()) {
                    val retainedFrame = cachedFrame
                    if (
                        retainedFrame != null &&
                        cachedFrameConstraints == constraints &&
                        cachedTreeRevision == revision
                    ) {
                        committedFrameConstraints = constraints
                        frameAvailable = true
                        retainedTree.monitoring.record(UiRenderMetric.FrameCacheHit)
                        return@runCatching retainedFrame
                    }
                }
                clearCachedFrame()
                frameAvailable = false
                val size = retainedTree.measure(constraints)
                retainedTree.layout()
                val draw = retainedTree.paint()
                val semantics = retainedTree.semantics()
                val frame = createRuntimeUiFrame(size, draw, semantics)
                committedFrameConstraints = constraints
                frameAvailable = true
                if (retainedTree.currentRevision() == revision) {
                    cachedFrameConstraints = constraints
                    cachedTreeRevision = revision
                    cachedFrame = frame
                }
                frame
            }.mapCatching { frame ->
                checkNotNull(tree) { "An attached session has no retained tree." }.finishFrameState()
                tree?.monitoring?.record(UiRenderMetric.FrameSuccess)
                frame
            }.getOrElse { failure ->
                tree?.monitoring?.record(UiRenderMetric.FrameFailure)
                tree?.monitoring?.failed()
                fail(failure)
            }
        } finally {
            endOperation()
        }
    }

    /**
     * Dispatches one pointer event through the most recently committed attached tree.
     *
     * Before the first successful frame, and while a newly reconciled tree has not completed a frame, input is consistently ignored.
     * Pending source values therefore never affect input before their frame is committed.
     * Dirty retained geometry is synchronized using the last committed constraints before dispatch, without producing a frame or rebuilding session content.
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
                inputTree().dispatchPointer(event)
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

    /**
     * Cancels captured pointer input and clears hover and focus without detaching this session.
     *
     * The owner thread may invoke this for window blur or an explicit native input reset while attached.
     * It is a no-op before a successful frame, preserves the committed frame and retained node ownership, and does not permit session-state mutation from cleanup callbacks.
     *
     * @throws IllegalStateException when called from another thread, reentrantly, or while the session is not attached.
     * @throws Throwable when input cleanup fails; the exact primary failure is preserved while remaining cleanup is attempted and the session fails.
     */
    internal fun resetInputState() {
        beginOperation(SessionOperation.InputReset)
        try {
            check(currentState === UiSessionState.Attached) {
                "A session can reset input only while Attached."
            }
            if (frameAvailable.not()) return
            runCatching {
                checkNotNull(tree) { "An attached session has no retained tree." }.clearInputState()
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

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
                dispatch(inputTree())
            }.getOrElse { failure -> fail(failure) }
        } finally {
            endOperation()
        }
    }

    private fun inputTree(): UiTree {
        check(operationKind === SessionOperation.Input)
        operationKind = SessionOperation.InputGeometry
        tree?.monitoring?.operation = UiRenderOperation.InputGeometry
        return try {
            val retainedTree = checkNotNull(tree) { "An attached session has no retained tree." }
            retainedTree.synchronizeInputGeometry(checkNotNull(committedFrameConstraints) { "Input requires a committed frame." })
            retainedTree
        } finally {
            operationKind = SessionOperation.Input
            tree?.monitoring?.operation = UiRenderOperation.Other
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
        stateObservation.enterOperation()
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
        retainedTree.monitoring.record(UiRenderMetric.RootEvaluation)
        clearCachedFrame()
        evaluatingContent = true
        val description =
            try {
                stateObservation.evaluate(checkNotNull(retainedContent) { "Session content has already been released." })
            } finally {
                evaluatingContent = false
            }
        dirty = false
        retainedTree.update(description)
        frameAvailable = false
    }

    private fun applyBindingCutoff() {
        bindings.forEach(UiSessionBinding<*>::capturePending)
        tree?.captureFrameState()
        bindings.forEach { binding ->
            if (binding.applyPending()) {
                dirty = true
            }
        }
        tree?.commitFrameState()
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
        stateObservation.enterOperation()
        tree?.monitoring?.operation =
            when (kind) {
                SessionOperation.Attach -> UiRenderOperation.Attach
                SessionOperation.Frame -> UiRenderOperation.Frame
                SessionOperation.InputGeometry -> UiRenderOperation.InputGeometry
                else -> UiRenderOperation.Other
            }
    }

    private fun endOperation() {
        stateObservation.leaveOperation()
        operationKind = null
        tree?.monitoring?.operation = UiRenderOperation.Other
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
        committedFrameConstraints = null
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
        stateObservation.close()
        retainedContent = null
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
        val generation = SessionGenerationToken.current ?: return
        check(
            generation.active && generation === currentGeneration?.token && currentState === UiSessionState.Attached,
        ) {
            "The coroutine generation is no longer active."
        }
    }

    private fun checkGenerationForStateAccess() {
        val generation = SessionGenerationToken.current
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
        val dispatcher = GenerationDispatcher()
        val context =
            job +
                dispatcher +
                token +
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
        val generation = SessionGenerationToken.current
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
        stateObservation.enterOperation()
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

    private enum class SessionOperation {
        Attach,
        Detach,
        Frame,
        Input,
        InputGeometry,
        InputReset,
        Close,
        Bind,
        TaskFailure,
    }

    private inner class SessionScreenScope : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = currentScreenScopeContext()
    }

    private class SessionGeneration(
        val token: SessionGenerationToken,
        val job: CompletableJob,
        val context: CoroutineContext,
    )

    private inner class GenerationDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatchOwner(context) {
                val generation = context[SessionGenerationToken]
                if (generation == null) block.run() else generation.resume(block)
            }
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
                generation.run { handleTaskFailure(generation, exception) }
            }
        }
    }

    private fun dispatchOwner(
        context: CoroutineContext,
        block: () -> Unit,
    ) {
        val dispatchThread = currentThread()
        val returned = AtomicBoolean(false)
        val violation = AtomicReference<Throwable?>(null)
        ownerDispatcher.dispatch(context) {
            if (returned.load().not() && currentThread() === dispatchThread) {
                val failure = IllegalStateException("The owner dispatcher must queue before execution.")
                violation.store(failure)
                throw failure
            }
            threadGuard.check()
            block()
        }
        returned.store(true)
        violation.load()?.let { failure -> throw failure }
    }
}
