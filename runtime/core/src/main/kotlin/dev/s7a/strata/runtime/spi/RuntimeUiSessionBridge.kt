@file:JvmName("RuntimeUiSessionFactory")

package dev.s7a.strata.runtime.spi

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.UiSession
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmName

/**
 * Creates one owner-thread runtime UI session bridge.
 *
 * Construction captures the current thread as the owner through the core session.
 * Synchronous lifecycle, frame, and input calls must already run on that owner thread.
 * The synchronous bridge exposes no coroutine task facility or dispatcher contract.
 * The content lambda is retained while the session is created, attached, or detached and is released before cleanup callbacks after failure or close.
 * Construction does not invoke [content].
 *
 * @param content the complete declarative element description, evaluated on the owner thread at first attachment and after observed state changes.
 * @return a private implementation exposing only the runtime bridge contract.
 */
@InternalStrataRuntimeApi
public fun createRuntimeUiSession(
    content: () -> Element,
): RuntimeUiSession = RuntimeUiSessionBridge(content)

/**
 * Adapts synchronous runtime calls to the session that owns lifecycle, frame caching, and cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // This adapter implements the complete lifecycle, rendering, input, and declaration contract.
private class RuntimeUiSessionBridge(
    content: () -> Element,
) : RuntimeUiSession {
    private val session = UiSession(SynchronousBridgeDispatcher, content = content)

    override val textInputFocus: RuntimeTextInputFocus?
        get() = session.textInputFocus

    override fun startRenderMonitoring(): UiRenderMonitor = session.startRenderMonitoring()

    override fun attach(): Unit = session.attach()

    override fun detach(): Unit = session.detach()

    override fun <T> projectDeclarations(project: (RuntimeDeclaration) -> T): T = session.projectDeclarations(project)

    override fun dispatchAction(action: () -> Unit): Unit = session.dispatchAction(action)

    override fun frame(constraints: Constraints): RuntimeUiFrame = session.frame(constraints)

    override fun frame(
        constraints: Constraints,
        time: FrameTime,
    ): RuntimeUiFrame = session.frame(constraints, time)

    override fun dispatchPointer(event: PointerEvent): InputResult = session.dispatchPointer(event)

    override fun dispatchKeyboard(event: KeyboardEvent): InputResult = session.dispatchKeyboard(event)

    override fun dispatchTextInput(event: TextInputEvent): InputResult = session.dispatchTextInput(event)

    override fun resetInputState(): Unit = session.resetInputState()

    override fun close(): Unit = session.close()

    private object SynchronousBridgeDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = throw IllegalStateException("The synchronous runtime bridge cannot dispatch coroutine work.")
    }
}
