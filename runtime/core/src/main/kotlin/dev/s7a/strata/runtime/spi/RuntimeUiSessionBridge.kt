@file:JvmName("RuntimeUiSessionFactory")

package dev.s7a.strata.runtime.spi

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.UiFrame
import dev.s7a.strata.runtime.UiSession
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Creates one owner-thread runtime UI session bridge.
 *
 * Construction captures the current thread as the owner through the core session.
 * Synchronous lifecycle, frame, and input calls must already run on that owner thread.
 * The synchronous bridge exposes no coroutine task facility or dispatcher contract.
 * The content lambda is retained while the session is created, attached, or detached and is released before cleanup callbacks after failure or close.
 * Construction does not invoke [content].
 *
 * @param content the complete element description, evaluated on the owner thread during the first attach.
 * @return a private implementation exposing only the runtime bridge contract.
 */
@InternalStrataRuntimeApi
public fun createRuntimeUiSession(
    content: () -> Element,
): RuntimeUiSession = RuntimeUiSessionImplementation.create(content)

/**
 * Owns the private implementation of the public runtime UI bridge.
 */
@OptIn(InternalStrataRuntimeApi::class)
private object RuntimeUiSessionImplementation {
    fun create(content: () -> Element): RuntimeUiSession = RuntimeUiSessionBridge.create(content)

    private class RuntimeUiSessionBridge private constructor(
        content: () -> Element,
    ) : RuntimeUiSession {
        private val session: UiSession = UiSession(SynchronousBridgeDispatcher, content = content)

        override fun attach() {
            session.attach()
        }

        override fun detach() {
            session.detach()
        }

        override fun frame(constraints: Constraints): RuntimeUiFrame =
            session.frame(constraints).let { frame ->
                RuntimeUiFrameSnapshot.create(frame)
            }

        override fun dispatchPointer(event: PointerEvent): InputResult = session.dispatchPointer(event)

        override fun close() {
            session.close()
        }

        companion object {
            /**
             * Creates the private session implementation without exposing a public construction hook.
             *
             * @param content the caller-owned retained content lambda.
             * @return the private session implementation.
             */
            @JvmSynthetic
            internal fun create(content: () -> Element): RuntimeUiSessionBridge = RuntimeUiSessionBridge(content)
        }
    }

    private class RuntimeUiFrameSnapshot private constructor(
        frame: UiFrame,
    ) : RuntimeUiFrame {
        // Why: UiFrame already owns defensive unmodifiable list snapshots, so reusing those lists avoids copying them on every frame.
        override val size: IntSize = frame.size
        override val drawCommands: List<DrawCommand> = frame.drawCommands
        override val semantics: List<SemanticsEntry> = frame.semantics

        companion object {
            /**
             * Creates a private immutable frame snapshot.
             *
             * @param frame the already immutable internal frame to retain.
             * @return a private frame implementation delegating immutable output.
             */
            @JvmSynthetic
            internal fun create(frame: UiFrame): RuntimeUiFrameSnapshot = RuntimeUiFrameSnapshot(frame)
        }
    }

    private object SynchronousBridgeDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = throw IllegalStateException("The synchronous runtime bridge cannot dispatch coroutine work.")
    }
}
