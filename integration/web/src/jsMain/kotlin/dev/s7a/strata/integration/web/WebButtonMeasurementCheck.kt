package dev.s7a.strata.integration.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.examples.web.counterDemo
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.width
import dev.s7a.strata.quality.benchmark.ComponentWorkload
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.WebUiHost
import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.runtime.web.renderWebHtml
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.json
import kotlin.js.unsafeCast
import kotlin.math.ceil

/**
 * Untimed shared controls for both runtime variants; normal states use independent fresh-render oracles.
 * The driver owns native fault injection/work traces and restores every descriptor in finally.
 * No clock, observer or instrumentation is installed in a timed operation or production runtime.
 */
internal object WebButtonMeasurementCheck {
    private val viewport = IntSize(640, 480)

    /**
     * Exports the exhaustive compiled registration independently of collected receipts.
     */
    fun inventory(): String =
        JSON.stringify(
            json(
                "contract" to "strata-web-button-measurement-controls-v1",
                "controls" to WebButtonMeasurementControl.entries.map { json("name" to it.name, "token" to it.token, "fault" to it.fault.name) }.toTypedArray(),
            ),
        )

    /**
     * Runs exactly one registered control under an explicit baseline/candidate expectation.
     */
    fun verify(
        theme: WebTheme,
        control: WebButtonMeasurementControl,
        fixedWidth: Boolean,
        probe: dynamic,
    ): String {
        val context = Context(theme, probe)
        val styles = document.querySelectorAll("style").length
        try {
            when (control) {
                WebButtonMeasurementControl.ObservedUiLabel,
                WebButtonMeasurementControl.ObservedUiEnabled,
                WebButtonMeasurementControl.ObservedUiBoth,
                WebButtonMeasurementControl.ObservedStringLabel,
                WebButtonMeasurementControl.ObservedStringEnabled,
                WebButtonMeasurementControl.ObservedStringBoth,
                WebButtonMeasurementControl.EqualSourcePublication,
                -> context.observed(control)
                WebButtonMeasurementControl.NegativeWidth,
                WebButtonMeasurementControl.TranslatedArguments,
                WebButtonMeasurementControl.ResourceFontRejection,
                WebButtonMeasurementControl.PlatformTextRejection,
                -> context.rejection(control)
                WebButtonMeasurementControl.AutomaticTextMeasureFailure,
                WebButtonMeasurementControl.AutomaticTextNullContext,
                WebButtonMeasurementControl.ButtonNullContext,
                WebButtonMeasurementControl.ButtonMeasureFailure,
                WebButtonMeasurementControl.ButtonCanvasCreationFailure,
                -> context.canvasFailure(control, fixedWidth)
                WebButtonMeasurementControl.AutomaticTextWidth -> context.automaticText()
                WebButtonMeasurementControl.EnabledPointerActivation,
                WebButtonMeasurementControl.DisabledPointerActivation,
                WebButtonMeasurementControl.FocusedKeyboardActivation,
                -> context.activation(control)
                WebButtonMeasurementControl.KeyedReorder,
                WebButtonMeasurementControl.RemoveInsert,
                -> context.topology(control)
                WebButtonMeasurementControl.InitialHtmlAdoption,
                WebButtonMeasurementControl.HydrationRejection,
                -> context.adoption(control)
                WebButtonMeasurementControl.IndependentHostThemes -> context.themes()
                WebButtonMeasurementControl.ModifierConstraints -> context.constraints()
                WebButtonMeasurementControl.IdleResize -> context.idleResize()
                WebButtonMeasurementControl.FailureCloseRelease -> context.failureRelease()
                else -> context.literal(control)
            }
            check(document.querySelectorAll("style").length == styles) { "A control retained a stylesheet" }
            context.checks["terminal_release"] = true
            context.checks["independent_full_render"] = context.snapshots.isNotEmpty()
            return JSON.stringify(json("ok" to true, "control" to control.token, "snapshots" to context.snapshots.toTypedArray(), "checks" to context.checks))
        } finally {
            context.removeRoots()
        }
    }

    /**
     * Owns only roots and detached screenshot states for one synchronous control.
     */
    @Suppress("TooManyFunctions")
    private class Context(
        private val theme: WebTheme,
        private val probe: dynamic,
    ) {
        val snapshots = ArrayList<dynamic>()
        val checks = json()
        private val roots = ArrayList<HTMLElement>()

        /**
         * Releases caller-owned roots after all mounted hosts have closed.
         */
        fun removeRoots() {
            roots.forEach { it.parentNode?.removeChild(it) }
            roots.clear()
        }

        /**
         * Exercises literal conversion, translated/concatenated labels, and changed width/label inputs.
         */
        fun literal(control: WebButtonMeasurementControl) {
            val initial =
                when (control) {
                    WebButtonMeasurementControl.ZeroWidth -> UiText.Literal("") to 0
                    WebButtonMeasurementControl.OneWidth -> UiText.Literal("A") to 1
                    WebButtonMeasurementControl.UnicodeLongLabel -> UiText.Literal("\u65e5\u672c\u8a9e \uD83D\uDE00 <script>& a long label without fitting") to 96
                    WebButtonMeasurementControl.TranslatedKey -> UiText.Translated("button.key") to 150
                    WebButtonMeasurementControl.TranslatedFallback -> UiText.Translated("button.key", "Fallback") to 150
                    WebButtonMeasurementControl.ConcatenatedLabel -> UiText.Concatenated(UiText.Literal("First"), UiText.Concatenated(UiText.Literal("/"), UiText.Literal("Last"))) to 96
                    else -> UiText.Literal("Initial") to 150
                }
            val inputs = mutableStateOf(initial)
            fun definition(): UiDefinition = UiDefinition {
                val (label, width) = inputs.value
                Column {
                    if (control == WebButtonMeasurementControl.LiteralString) Button((label as UiText.Literal).value, width) else Button(label, width)
                }
            }
            val root = root()
            val host = work(1) { mountWeb(definition(), root, viewport, theme) }
            try {
                compare(root, definition(), "initial")
                val original = root.firstElementChild
                if (control == WebButtonMeasurementControl.ZeroWidth) check(original == null) else check(original?.textContent == resolve(initial.first))
                inputs.value = UiText.Literal("Changed <b>&") to (initial.second + 3)
                work(1) { host.render(viewport) }
                if (original != null) check(root.firstElementChild === original)
                check(root.firstElementChild?.textContent == resolve(inputs.value.first))
                check(root.querySelector("b") == null)
                compare(root, definition(), "changed-label-width")
            } finally {
                close(host, root)
            }
            if (control == WebButtonMeasurementControl.LiteralString) shippedDeclarations()
        }

        /**
         * Traces the shipped canonical Button, Reactive and Counter declarations without timing hooks.
         */
        private fun shippedDeclarations() {
            val canonical = root()
            val canonicalSize = WebComponentPerformanceContract.viewport
            val canonicalHost = work(1, scope = "canonical-button-initial") { mountWeb(ComponentWorkload.Button.uiDefinition(), canonical, canonicalSize, theme) }
            try {
                compare(canonical, ComponentWorkload.Button.uiDefinition(), "canonical-button", size = canonicalSize)
            } finally {
                close(canonicalHost, canonical)
            }
            val reactive = ReactiveScenario()
            val reactiveRoot = root()
            val reactiveHost = work(4, 4, "reactive-initial") { mountWeb(reactive.definition(), reactiveRoot, ReactiveScenario.viewport, theme) }
            try {
                compare(reactiveRoot, reactive.definition(), "reactive-initial", size = ReactiveScenario.viewport)
                reactive.toggle()
                work(4, 3, "reactive-update") { reactiveHost.render(ReactiveScenario.viewport) }
                compare(reactiveRoot, reactive.definition(), "reactive-update", size = ReactiveScenario.viewport)
                work(4, 3, "reactive-input") { press(button(reactiveRoot, "Reorder")) }
                compare(reactiveRoot, reactive.definition(), "reactive-input", size = ReactiveScenario.viewport)
            } finally {
                close(reactiveHost, reactiveRoot)
            }
            val counter = root()
            val counterHost = work(3, 2, "counter-initial") { mountWeb(counterDemo(), counter, viewport, theme) }
            try {
                compare(counter, counterDemo(), "counter-initial")
                work(3, 2, "counter-input") { press(button(counter, "Increase")) }
                compare(counter, counterDemo(), "counter-input", prepareReference = { _, root -> press(button(root, "Increase")) })
            } finally {
                close(counterHost, counter)
            }
        }

        /**
         * Compiles each source overload and verifies deferred cutoff, equal revisions and release.
         */
        fun observed(control: WebButtonMeasurementControl) {
            val ui = WebButtonMeasurementSource<UiText>(UiText.Literal("Initial"))
            val text = WebButtonMeasurementSource("Initial")
            val enabled = WebButtonMeasurementSource(true)
            val root = root()
            fun definition(): UiDefinition = UiDefinition { Column { emitObserved(control, ui, text, enabled) } }
            val host = work(1) { mountWeb(definition(), root, viewport, theme) }
            try {
                val original = checkNotNull(root.firstElementChild)
                compare(root, definition(), "initial")
                if (control == WebButtonMeasurementControl.EqualSourcePublication) {
                    ui.publish(UiText.Literal("Initial"))
                    enabled.publish(true)
                    work(0) { host.render(viewport) }
                    check(root.firstElementChild === original)
                    compare(root, definition(), "equal-publication")
                } else {
                    val changesLabel = (control in setOf(WebButtonMeasurementControl.ObservedUiEnabled, WebButtonMeasurementControl.ObservedStringEnabled)).not()
                    val changesEnabled = (control in setOf(WebButtonMeasurementControl.ObservedUiLabel, WebButtonMeasurementControl.ObservedStringLabel)).not()
                    if (changesLabel) {
                        ui.publish(UiText.Literal("Changed"))
                        text.publish("Changed")
                    }
                    if (changesEnabled) enabled.publish(false)
                    check(original.textContent == resolve(UiText.Literal("Initial"))) { "External publication changed DOM before the owner cutoff" }
                    work(1) { host.render(viewport) }
                    check(root.firstElementChild === original)
                    check(original.textContent == if (changesLabel) "Changed" else "Initial")
                    check((original as HTMLButtonElement).disabled == changesEnabled)
                    compare(root, definition(), "committed")
                    ui.publish(UiText.Literal(if (changesLabel) "Changed" else "Initial"))
                    text.publish(if (changesLabel) "Changed" else "Initial")
                    enabled.publish(changesEnabled.not())
                    work(0) { host.render(viewport) }
                    check(root.firstElementChild === original)
                }
            } finally {
                close(host, root)
                check(ui.active == 0 && text.active == 0 && enabled.active == 0)
            }
        }

        /**
         * Resolver and geometry failures remain exact typed failures without a mounted session.
         */
        fun rejection(control: WebButtonMeasurementControl) {
            val label =
                when (control) {
                    WebButtonMeasurementControl.TranslatedArguments -> UiText.Translated("button.key", listOf(UiTextArgument.StringValue("argument")))
                    WebButtonMeasurementControl.ResourceFontRejection -> UiText.WithFont(UiText.Literal("Font"), ResourceId("fixture", "font"))
                    WebButtonMeasurementControl.PlatformTextRejection -> UiText.Platform(RejectedPlatformText)
                    else -> UiText.Literal("Negative")
                }
            val root = root()
            val failure = work(if (control == WebButtonMeasurementControl.NegativeWidth) 1 else 0) {
                runCatching { mountWeb(UiDefinition { Button(label, if (control == WebButtonMeasurementControl.NegativeWidth) -1 else 150) }, root, viewport, theme) }.exceptionOrNull()
            }
            checkNotNull(failure)
            when (control) {
                WebButtonMeasurementControl.ResourceFontRejection, WebButtonMeasurementControl.PlatformTextRejection -> check(failure is UnsupportedOperationException)
                else -> check(failure is IllegalArgumentException)
            }
            check(root.hasChildNodes().not())
            checks["mounted"] = false
            checks["failure"] = failure.toString()
        }

        /**
         * Button no longer requires Canvas; Text still preserves its original primary failure.
         */
        fun canvasFailure(
            control: WebButtonMeasurementControl,
            fixedWidth: Boolean,
        ) {
            probe.setFailure(IllegalStateException("Injected Canvas unavailable"))
            val text = control in setOf(WebButtonMeasurementControl.AutomaticTextMeasureFailure, WebButtonMeasurementControl.AutomaticTextNullContext)
            val root = root()
            var host: WebUiHost? = null
            val result = work(if (text) 0 else 1, if (text) 1 else 0) {
                runCatching { mountWeb(UiDefinition { Column { if (text) Text("Automatic") else Button("Fixed", 96) } }, root, viewport, theme).also { host = it } }
            }
            try {
                if (text || fixedWidth.not()) {
                    val failure = checkNotNull(result.exceptionOrNull())
                    if (control.fault == WebButtonMeasurementControl.CanvasFault.NullContext) check(failure is IllegalStateException) else check(probe.sameFailure(failure) as Boolean)
                    check(root.hasChildNodes().not())
                } else {
                    check(result.isSuccess)
                    check(root.firstElementChild?.textContent == resolve(UiText.Literal("Fixed")))
                    check(measuredSize(element(root, 0)) == IntSize(96, 32))
                    compare(root, UiDefinition { Column { Button("Fixed", 96) } }, "canvas-independent-button")
                }
                checks["intentional_button_canvas_dependency_change"] = text.not()
                checks["mounted"] = result.isSuccess
                checks["failure"] = result.exceptionOrNull()?.toString()
            } finally {
                host?.let { close(it, root) }
            }
        }

        /**
         * Natural Text width still uses actual themed Canvas, including a width-modified Text control.
         */
        fun automaticText() {
            fun definition(): UiDefinition = UiDefinition {
                Column {
                    Button("Fixed", 96)
                    Text("Automatic")
                    Text("Width modified", modifier = Modifier.Empty.width(80))
                }
            }
            val root = root()
            val host = work(1, 2) { mountWeb(definition(), root, viewport, theme) }
            try {
                probe.pause()
                try {
                    val text = element(root, 1)
                    val canvas = document.createElement("canvas") as HTMLCanvasElement
                    val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
                    context.font = window.getComputedStyle(text).font
                    check(measuredSize(text).width == ceil(context.measureText("Automatic").width).toInt())
                    check(measuredSize(element(root, 2)).width == 80)
                } finally { probe.resume() }
                compare(root, definition(), "automatic-and-modified-text")
            } finally { close(host, root) }
        }

        /**
         * Native pointer actions retain caller enablement; Web installs no native keyboard forwarding.
         */
        fun activation(control: WebButtonMeasurementControl) {
            val enabled = control != WebButtonMeasurementControl.DisabledPointerActivation
            val clicks = mutableStateOf(0)
            fun definition(): UiDefinition = UiDefinition { Column { Button("Clicks ${clicks.value}", enabled = enabled, modifier = Modifier.Empty.onActivate(enabled) { clicks.value += 1 }) } }
            val root = root()
            val host = work(1) { mountWeb(definition(), root, viewport, theme) }
            try {
                val button = root.firstElementChild as HTMLButtonElement
                if (control == WebButtonMeasurementControl.FocusedKeyboardActivation) {
                    button.focus()
                    check(document.activeElement === button)
                    work(0) {
                        listOf("Enter", " ").forEach { key -> button.dispatchEvent(KeyboardEvent("keydown", json("key" to key, "bubbles" to true).unsafeCast<KeyboardEventInit>())) }
                        host.render(viewport)
                    }
                    check(clicks.value == 0)
                    checks["native_keyboard_forwarding"] = false
                } else {
                    work(if (enabled) 1 else 0) {
                        press(button)
                    }
                    check(clicks.value == if (enabled) 1 else 0)
                    check(root.firstElementChild === button)
                }
                compare(root, definition(), "activation")
            } finally { close(host, root) }
        }

        /**
         * Keyed movement and removal preserve live identities and release obsolete native owners.
         */
        fun topology(control: WebButtonMeasurementControl) {
            val order = mutableStateOf(listOf(1, 2, 3))
            fun definition(): UiDefinition = UiDefinition { Column { order.value.forEach { Button("Button $it", 100, key = ElementKey(it)) } } }
            val root = root()
            val host = work(3) { mountWeb(definition(), root, viewport, theme) }
            try {
                val original = (0 until 3).map { element(root, it) }
                original[0].focus()
                check(document.activeElement === original[0])
                order.value = if (control == WebButtonMeasurementControl.KeyedReorder) listOf(3, 2, 1) else listOf(3, 1)
                work(order.value.size) { host.render(viewport) }
                check(element(root, 0) === original[2])
                check(element(root, order.value.size - 1) === original[0])
                check(document.activeElement === original[0])
                compare(root, definition(), "reordered-or-removed")
                if (control == WebButtonMeasurementControl.RemoveInsert) {
                    check(original[1].parentNode == null)
                    order.value = listOf(3, 1, 2)
                    work(3) { host.render(viewport) }
                    check(element(root, 2) !== original[1])
                    compare(root, definition(), "fresh-insertion")
                }
            } finally { close(host, root) }
        }

        /**
         * Generated HTML is adopted by identity, while mismatches preserve the caller's markup.
         */
        fun adoption(control: WebButtonMeasurementControl) {
            fun definition(label: String = "Initial"): UiDefinition = UiDefinition { Column { Button(label) } }
            val root = root()
            root.innerHTML = work(1) { renderWebHtml(definition(), viewport, theme) }
            val original = checkNotNull(root.firstElementChild)
            if (control == WebButtonMeasurementControl.HydrationRejection) {
                val html = root.innerHTML
                val failure = work(1) { runCatching { mountWeb(definition("Mismatch"), root, viewport, theme) }.exceptionOrNull() }
                check(failure is IllegalStateException)
                check(root.innerHTML == html && root.firstElementChild === original)
                checks["unchanged_rejected_markup"] = true
            } else {
                val host = work(1) { mountWeb(definition(), root, viewport, theme) }
                try {
                    check(root.firstElementChild === original)
                    compare(root, definition(), "adopted")
                } finally {
                    close(host, root)
                }
            }
        }

        /**
         * Concurrent hosts use distinct theme/style owners without any shared measurement state.
         */
        fun themes() {
            val roots = listOf(root(), root())
            val themes = listOf(WebTheme.Native, WebTheme.Minecraft)
            val hosts = ArrayList<WebUiHost>()
            try {
                roots.forEachIndexed { index, root -> hosts.add(work(1) { mountWeb(UiDefinition { Column { Button("Independent", 96) } }, root, viewport, themes[index]) }) }
                check(element(roots[0], 0).getAttribute("data-strata-theme") != element(roots[1], 0).getAttribute("data-strata-theme"))
                roots.forEachIndexed { index, root -> compare(root, UiDefinition { Column { Button("Independent", 96) } }, "host-$index", themes[index]) }
                close(hosts[0], roots[0])
                check(roots[1].hasChildNodes())
            } finally { hosts.forEachIndexed { index, host -> close(host, roots[index]) } }
        }

        /**
         * Active size modifiers retain the existing constrained geometry and independent property parity.
         */
        fun constraints() {
            val small = IntSize(84, 15)
            fun definition(): UiDefinition = UiDefinition { Column { Button("Constrained", 150, modifier = Modifier.Empty.size(100, 20)) } }
            val root = root()
            val host = work(1) { mountWeb(definition(), root, small, theme) }
            try {
                check(measuredSize(element(root, 0)) == small)
                compare(root, definition(), "constrained", size = small)
            } finally { close(host, root) }
        }

        /**
         * Clean and viewport-only frames keep native identity without declaration-time Canvas work.
         */
        fun idleResize() {
            val source = WebButtonMeasurementSource("Idle")
            fun definition(): UiDefinition = UiDefinition { Column { Button(source) } }
            val root = root()
            val host = work(1) { mountWeb(definition(), root, viewport, theme) }
            try {
                val original = root.firstElementChild
                work(0) {
                    host.render(viewport)
                    host.render(IntSize(600, 440))
                }
                check(root.firstElementChild === original)
                compare(root, definition(), "idle-resize", size = IntSize(600, 440))
            } finally {
                close(host, root)
                check(source.active == 0)
            }
        }

        /**
         * Declaration and native rendering faults keep primary failures and release source/style ownership.
         */
        fun failureRelease() {
            val primary = IllegalStateException("Button declaration failed")
            val cleanup = IllegalStateException("Button source cleanup failed")
            val source = WebButtonMeasurementSource("Ready", cleanup)
            val fail = mutableStateOf(false)
            val root = root()
            val host = work(1) {
                mountWeb(
                    UiDefinition {
                        Column {
                            Button(source)
                            if (fail.value) throw primary
                        }
                    },
                    root,
                    viewport,
                    theme,
                )
            }
            compare(root, UiDefinition { Column { Button("Ready") } }, "before-declaration-failure")
            fail.value = true
            val failure = work(0) { runCatching { host.render(viewport) }.exceptionOrNull() }
            check(failure === primary)
            check(primary.suppressedExceptions.firstOrNull() === cleanup)
            host.close()
            host.close()
            check(root.hasChildNodes().not() && source.active == 0)
            val rendering = IllegalStateException("Native Button insertion failed")
            val renderSource = WebButtonMeasurementSource("Insert")
            val renderRoot = root()
            val native = renderRoot.asDynamic().insertBefore
            renderRoot.asDynamic().insertBefore = { _: dynamic, _: dynamic -> throw rendering }
            try {
                val renderFailure = work(1) { runCatching { mountWeb(UiDefinition { Button(renderSource) }, renderRoot, viewport, theme) }.exceptionOrNull() }
                check(renderFailure === rendering)
                check(renderRoot.hasChildNodes().not() && renderSource.active == 0)
            } finally { renderRoot.asDynamic().insertBefore = native }
            checks["primary_and_suppression_order"] = true
        }

        /**
         * Creates a caller-owned root that survives only this control.
         */
        private fun root(): HTMLElement =
            (document.createElement("div") as HTMLElement).also {
                checkNotNull(document.body).appendChild(it)
                roots.add(it)
            }

        /**
         * Bounds native counting to one operation; failures still close the trace interval.
         */
        private fun <T> work(
            buttons: Int,
            texts: Int = 0,
            scope: String = "control",
            action: () -> T,
        ): T {
            probe.begin(buttons, texts, scope)
            return try {
                action()
            } finally {
                probe.end()
            }
        }

        /**
         * Checks repeated terminal close and immediate native child release.
         */
        private fun close(host: WebUiHost, root: HTMLElement) {
            host.close()
            host.close()
            check(root.hasChildNodes().not())
        }

        /**
         * Captures detached pixels only after exact native property parity with a fresh host.
         */
        private fun compare(
            root: HTMLElement,
            definition: UiDefinition,
            phase: String,
            referenceTheme: WebTheme = theme,
            size: IntSize = viewport,
            prepareReference: (WebUiHost, HTMLElement) -> Unit = { _, _ -> },
        ) {
            probe.pause()
            val reference = root()
            try {
                mountWeb(definition, reference, size, referenceTheme).use { host ->
                    prepareReference(host, reference)
                    check(properties(root) == properties(reference)) { "Button state differs from independent rendering: $phase" }
                    val styles = document.querySelectorAll("style")
                    val css = (0 until styles.length).joinToString("") { (checkNotNull(styles.item(it)) as HTMLElement).outerHTML }
                    val focus = (0 until root.children.length).firstOrNull { root.children.item(it) === document.activeElement }
                    snapshots.add(json("phase" to phase, "currentHtml" to root.outerHTML, "referenceHtml" to reference.outerHTML, "stylesHtml" to css, "focusIndex" to focus))
                }
            } finally {
                reference.parentNode?.removeChild(reference)
                probe.resume()
            }
        }

        /**
         * Compares all rendered styles/native properties while identity remains a separate live oracle.
         */
        private fun properties(root: HTMLElement): List<List<Any?>> = (0 until root.children.length).map { index ->
            val element = element(root, index)
            listOf(element.tagName, element.textContent, element.style.cssText, element.getAttribute("data-strata-theme"), (element as? HTMLButtonElement)?.type, (element as? HTMLButtonElement)?.disabled)
        }

        /**
         * Converts the renderer's integer pixel dimensions into the existing typed geometry oracle.
         */
        private fun measuredSize(element: HTMLElement): IntSize =
            IntSize(
                element.style.width.removeSuffix("px").toInt(),
                element.style.height.removeSuffix("px").toInt(),
            )

        /**
         * Retrieves one required native presentation child.
         */
        private fun element(root: HTMLElement, index: Int): HTMLElement = checkNotNull(root.children.item(index)) as HTMLElement

        /**
         * Selects one actual action surface by its resolved external DOM label.
         */
        private fun button(root: HTMLElement, label: String): HTMLButtonElement =
            (0 until root.children.length).map { element(root, it) }.filterIsInstance<HTMLButtonElement>().single { it.textContent == label }

        /**
         * Delivers the existing native pointer route with root-relative actual geometry.
         */
        private fun press(button: HTMLButtonElement) {
            val bounds = button.getBoundingClientRect()
            button.dispatchEvent(MouseEvent("pointerdown", json("bubbles" to true, "clientX" to bounds.left + 1, "clientY" to bounds.top + 1, "button" to 0).unsafeCast<MouseEventInit>()))
        }

        /**
         * Independent expected labels for successful resolver controls only.
         */
        private fun resolve(text: UiText): String = when (text) {
            is UiText.Literal -> text.value
            is UiText.Translated ->
                when (val fallback = text.fallback) {
                    is TranslationFallback.Literal -> fallback.value
                    else -> text.key
                }
            is UiText.Concatenated -> text.parts.joinToString("") { resolve(it) }
            else -> error("No rejected text belongs to a successful literal control")
        }

        /**
         * Calls each public source overload directly rather than erasing it behind a literal wrapper.
         */
        private fun UiScope.emitObserved(
            control: WebButtonMeasurementControl,
            ui: WebButtonMeasurementSource<UiText>,
            text: WebButtonMeasurementSource<String>,
            enabled: WebButtonMeasurementSource<Boolean>,
        ) {
            when (control) {
                WebButtonMeasurementControl.ObservedUiLabel -> Button(ui)
                WebButtonMeasurementControl.ObservedUiEnabled -> Button(UiText.Literal("Initial"), enabled = enabled)
                WebButtonMeasurementControl.ObservedUiBoth, WebButtonMeasurementControl.EqualSourcePublication -> Button(ui, enabled = enabled)
                WebButtonMeasurementControl.ObservedStringLabel -> Button(text)
                WebButtonMeasurementControl.ObservedStringEnabled -> Button("Initial", enabled = enabled)
                WebButtonMeasurementControl.ObservedStringBoth -> Button(text, enabled = enabled)
                else -> error("Expected an observed Button overload")
            }
        }

        /**
         * Immutable platform payload used only to prove Web's resolver rejection.
         */
        private data object RejectedPlatformText : PlatformText
    }
}
