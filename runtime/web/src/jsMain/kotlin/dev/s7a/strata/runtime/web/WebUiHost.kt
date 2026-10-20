package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Owns one browser-agent retained session and the contents of a caller-owned DOM root.
 * The host polls frames through requestAnimationFrame; unchanged frames reuse the current DOM without rebuilding it.
 * Closing cancels the animation request, removes listeners and owned children, and releases application content through core cleanup.
 * The caller owns the root itself and must not independently mutate its children while mounted.
 */
@OptIn(InternalStrataRuntimeApi::class)
public class WebUiHost internal constructor(
    root: HTMLElement,
    private val session: RuntimeUiSession,
    private var viewport: IntSize,
    private val controls: RuntimeUiController,
    theme: WebTheme = WebTheme.Native,
    runtime: WebComponentRuntime = WebComponentRuntime(theme),
) : AutoCloseable {
    /**
     * Stable event receiver; unsupported game presentation/input controls return an explicit rejection.
     */
    public val uiSession: UiSession get() = controls

    private val renderer = WebDomRenderer(root, theme, runtime.resources)
    private var lastFrame: RuntimeUiFrame? = null
    private var request: Int? = null
    private var closed = false
    private var rendering = false
    private var pixelRatio = window.devicePixelRatio
    private val events =
        WebInputEvents(root, session, runtime) { action ->
            if (rendering.not() && closed.not()) {
                guarded {
                    action()
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
            events.start()
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
            if (frame !== lastFrame || pixelRatio != window.devicePixelRatio) {
                pixelRatio = window.devicePixelRatio
                rendering = true
                try {
                    renderer.render(frame)
                    events.synchronizeEditors()
                } finally {
                    rendering = false
                }
                lastFrame = frame
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        request?.let(window::cancelAnimationFrame)
        request = null
        events.close()
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
 * @param definition available one-shot screen definition created on this agent.
 * @param root caller-owned DOM container.
 * @param viewport non-negative initial logical viewport.
 * @return mounted host owning its session, DOM children, listeners, and animation request.
 * @throws Throwable on transfer, content, layout, or rendering failure; partially mounted resources are released.
 */
public fun mountWeb(
    definition: UiDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = createWebHost(definition, root, viewport).also(WebUiHost::start)

/**
 * Mounts a themed screen using the ownership and failure contract of [mountWeb].
 * The theme must match initial build rendering; mismatches fail before changing existing HTML.
 */
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
    resources: WebUiResources = WebUiResources(),
): WebUiHost {
    require(definition.presentation == UiPresentation.Screen) {
        "The Web runtime supports Screen presentation."
    }
    val runtime = WebComponentRuntime(theme, resources)
    val transferred = definition.transfer()
    var host: WebUiHost? = null
    lateinit var controls: RuntimeUiController
    controls =
        RuntimeUiController(UiPresentation.Screen, definition.inputPolicy, apply = { request ->
            if (request.presentation != UiPresentation.Screen || request.interactionMode != UiInteractionMode.Cursor) {
                controls.rejected(request.sequence, UiRejection.Unsupported)
            } else {
                controls.applied(request.sequence)
            }
        }, close = { host?.close() })
    val session = createRuntimeUiSession(controls) { ComponentRuntimeBridge.evaluate(runtime, transferred.content) }
    return WebUiHost(root, session, viewport, controls, theme, runtime).also { host = it }
}

/**
 * Mounts one shared definition with a caller-prepared resource snapshot.
 * Fonts and skin sources remain caller-owned and must outlive this host.
 * Game input policies are preserved as metadata; the browser has no gameplay forwarding target.
 */
public fun mountWeb(
    definition: UiDefinition,
    root: HTMLElement,
    viewport: IntSize,
    resources: WebUiResources,
    theme: WebTheme = WebTheme.Native,
): WebUiHost = createWebHost(definition, root, viewport, theme, resources).also(WebUiHost::start)

/**
 * Compatibility overload retaining the same common UI session and browser ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("DEPRECATION")
public fun mountWeb(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = mountWeb(definition.asUiDefinition(), root, viewport)

/**
 * Compatibility overload for deterministic HTML generation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("DEPRECATION")
internal fun createWebHost(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
): WebUiHost = createWebHost(definition.asUiDefinition(), root, viewport)

/**
 * Compatibility overload for mounting a themed screen.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("DEPRECATION")
public fun mountWeb(
    definition: ScreenDefinition,
    root: HTMLElement,
    viewport: IntSize,
    theme: WebTheme,
): WebUiHost = mountWeb(definition.asUiDefinition(), root, viewport, theme)
