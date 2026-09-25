@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent
import kotlin.math.floor

/**
 * Owns one browser-agent retained session and the contents of a caller-owned DOM root.
 * The host polls frames through requestAnimationFrame; unchanged frames reuse the current DOM without rebuilding it.
 * Closing cancels the animation request, removes listeners and owned children, and releases application content through core cleanup.
 * The caller owns the root itself and must not independently mutate its children while mounted.
 */
@OptIn(InternalStrataRuntimeApi::class)
public class WebUiHost internal constructor(
    private val root: HTMLElement,
    private val session: RuntimeUiSession,
    private var viewport: IntSize,
    private val controls: RuntimeUiController,
    theme: WebTheme = WebTheme.Native,
) : AutoCloseable {
    /**
     * Stable event receiver; unsupported game presentation/input controls return an explicit rejection.
     */
    public val uiSession: UiSession get() = controls

    private val renderer = WebDomRenderer(root, theme)
    private var lastFrame: RuntimeUiFrame? = null
    private var request: Int? = null
    private var closed = false
    private val pressListener =
        EventListener { event ->
            val mouse = event as? MouseEvent
            if (mouse != null) {
                guarded {
                    val bounds = root.getBoundingClientRect()
                    val position = IntOffset(floor(mouse.clientX - bounds.left).toInt(), floor(mouse.clientY - bounds.top).toInt())
                    val button =
                        when (val value = mouse.button.toInt()) {
                            0 -> PointerButton.Primary
                            1 -> PointerButton.Middle
                            2 -> PointerButton.Secondary
                            else -> PointerButton.Auxiliary(value)
                        }
                    if (session.dispatchPointer(PointerEvent.Press(position, button)) == InputResult.Consumed) mouse.preventDefault()
                    render(viewport)
                }
            }
        }

    /**
     * Attaches and paints the initial frame before accepting native events or scheduling later frames.
     * A failure closes the partially mounted host and preserves the initial failure.
     */
    internal fun start() {
        guarded {
            prepare()
            root.addEventListener("pointerdown", pressListener)
            schedule()
        }
    }

    /**
     * Produces the initial DOM without native listeners or animation requests, for deterministic build rendering.
     */
    internal fun prepare() {
        guarded {
            session.attach()
            render(viewport)
            controls.start()
        }
    }

    /**
     * Applies pending state changes synchronously at the supplied non-negative logical viewport.
     * The next animation frame continues using this viewport; failures propagate with core cleanup semantics.
     */
    public fun render(viewport: IntSize) {
        check(closed.not()) { "The web host is closed." }
        this.viewport = viewport
        guarded {
            val frame = session.frame(Constraints.fixed(viewport.width, viewport.height))
            if (frame !== lastFrame) {
                renderer.render(frame)
                lastFrame = frame
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        request?.let(window::cancelAnimationFrame)
        request = null
        root.removeEventListener("pointerdown", pressListener)
        lastFrame = null
        val controlFailure = runCatching { controls.close() }.exceptionOrNull()
        val sessionFailure = runCatching { session.close() }.exceptionOrNull()
        val primary = controlFailure ?: sessionFailure
        if (controlFailure != null && sessionFailure != null && controlFailure !== sessionFailure) controlFailure.addSuppressed(sessionFailure)
        val cleanup = runCatching { renderer.close() }.exceptionOrNull()
        if (primary != null) {
            if (cleanup != null && cleanup !== primary) primary.addSuppressed(cleanup)
            throw primary
        }
        if (cleanup != null) throw cleanup
    }

    private fun <T> guarded(action: () -> T): T =
        runCatching { controls.transaction(action) }.getOrElse { failure ->
            runCatching { close() }.exceptionOrNull()?.let { cleanup -> if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }

    private fun schedule() {
        if (closed) return
        request =
            window.requestAnimationFrame {
                request = null
                if (closed.not()) {
                    render(viewport)
                    schedule()
                }
            }
    }
}

/**
 * Transfers one screen definition into an independently owned browser host and immediately renders its initial DOM.
 * Runs synchronously on the browser agent and requires exclusive ownership of the root's children until host close.
 * The returned host retains application content through the shared core session, allowing ordinary Kotlin conditionals to reevaluate.
 * Existing generated children are validated and reused; mismatched initial content fails before modifying that HTML.
 * Build and browser must create independent definitions from the same deterministic initial values.
 *
 * @param definition available one-shot screen definition created on this agent.
 * @param root caller-owned DOM container.
 * @param viewport non-negative initial logical viewport.
 * @return mounted host owning its session, DOM children, listeners, and animation request.
 * @throws Throwable on transfer, content, layout, or rendering failure; partially mounted resources are released.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun mountWeb(
    definition: UiDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = createWebHost(definition, root, viewport).also(WebUiHost::start)

/**
 * Mounts a themed screen using the ownership and failure contract of [mountWeb].
 * The theme must match initial build rendering; mismatches fail before changing existing HTML.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun mountWeb(
    definition: UiDefinition,
    root: HTMLElement,
    viewport: IntSize,
    theme: WebTheme,
): WebUiHost = createWebHost(definition, root, viewport, theme).also(WebUiHost::start)

/**
 * Transfers a definition to an unstarted host whose caller must prepare or start it and eventually close it.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createWebHost(
    definition: UiDefinition,
    root: HTMLElement,
    viewport: IntSize,
    theme: WebTheme = WebTheme.Native,
): WebUiHost {
    require(definition.presentation == UiPresentation.Screen && definition.inputPolicy == UiInputPolicy.BlockAll) {
        "The Web runtime supports Screen presentation without game input forwarding."
    }
    val runtime = WebComponentRuntime(theme)
    val transferred = definition.transfer()
    var host: WebUiHost? = null
    lateinit var controls: RuntimeUiController
    controls =
        RuntimeUiController(UiPresentation.Screen, apply = { request ->
            if (request.presentation != UiPresentation.Screen || request.inputPolicy != UiInputPolicy.BlockAll || request.interactionMode != UiInteractionMode.Cursor) {
                controls.rejected(request.sequence, UiRejection.Unsupported)
            } else {
                controls.applied(request.sequence)
            }
        }, close = { host?.close() })
    val session = createRuntimeUiSession(controls) { ComponentRuntimeBridge.evaluate(runtime, transferred.content) }
    return WebUiHost(root, session, viewport, controls, theme).also { host = it }
}

/**
 * Compatibility overload retaining the same common UI session and browser ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun mountWeb(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = mountWeb(definition.asUiDefinition(), root, viewport)

/**
 * Compatibility overload for deterministic HTML generation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createWebHost(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = createWebHost(definition.asUiDefinition(), root, viewport)

/**
 * Compatibility overload for mounting a themed screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun mountWeb(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
    theme: WebTheme,
): WebUiHost = mountWeb(definition.asUiDefinition(), root, viewport, theme)
