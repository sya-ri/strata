package dev.s7a.strata.integration.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.performance.BrowserPerformanceJson
import dev.s7a.strata.performance.BrowserPerformanceMeter
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.WebUiHost
import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Promise
import kotlin.js.json
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Compiled changed-frame Web workloads independent of the shipped standard-component controls.
 * Publication happens before sampling; the common meter times synchronous host application and its next animation frame separately.
 * Untimed probes use the same operation with independent full-render parity and stable native ownership assertions.
 */
internal class WebDomPerformanceFixture private constructor(
    private val theme: WebTheme,
    private val mode: Mode,
    private val count: Int,
) : AutoCloseable {
    private val active = mutableStateOf(false)
    private val order = (0 until count).toList()
    private val reversed = order.reversed()
    private val viewport = IntSize(320, count * 32 + 40)
    private var nextViewport = viewport
    private val container =
        (document.createElement("div") as HTMLElement).apply {
            style.position = "absolute"
            style.left = "0px"
            style.top = "0px"
        }
    private val root = (document.createElement("div") as HTMLElement).also(container::appendChild)
    private var host: WebUiHost? = null
    private var initialElements = emptyList<HTMLElement>()

    private fun mount() {
        checkNotNull(document.body).appendChild(container)
        host = mountWeb(definition { active.value }, root, viewport, theme)
        initialElements = elements(root)
        check(initialElements.size == count)
    }

    private fun publish(
        index: Int,
    ) {
        val changed = index % 2 == 0
        active.value = changed
        nextViewport = if (mode == Mode.Resize && changed) IntSize(viewport.width + 20, viewport.height + 10) else viewport
    }

    private fun apply() {
        checkNotNull(host).render(nextViewport)
    }

    private fun verifySample() {
        val current = elements(root)
        check(current.size == count) { "Changed frame altered the declared native element count" }
        val expected = if (mode == Mode.Reorder && active.value) initialElements.reversed() else initialElements
        check(current == expected) { "Changed frame replaced or reordered a retained native identity unexpectedly" }
        when (mode) {
            Mode.Button -> check((current.first() as HTMLButtonElement).disabled == active.value)
            Mode.Progress -> check((current.first() as HTMLProgressElement).value == if (active.value) 0.75 else 0.25)
            else -> Unit
        }
    }

    private fun verifyWork(): Boolean {
        verifySample()
        val reference = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(reference)
        val changed = active.value
        try {
            mountWeb(definition { changed }, reference, nextViewport, theme).use {
                check(root.style.cssText == reference.style.cssText)
                check(elements(root).map(::properties) == elements(reference).map(::properties)) { "Changed-frame DOM differs from an independent full render" }
            }
        } finally {
            reference.parentNode?.removeChild(reference)
        }
        return true
    }

    private fun measure(): Promise<String> =
        BrowserPerformanceMeter()
            .measure(
                ready = { root.isConnected },
                beforeSample = { index -> publish(index) },
                operation = { apply() },
                afterSample = { verifySample() },
                cleanup = ::close,
            ).then { BrowserPerformanceJson.sample(it) }

    private fun definition(
        value: () -> Boolean,
    ): UiDefinition =
        UiDefinition("Changed DOM frame") {
            val changed = value()
            if (mode == Mode.Clip) {
                element(ClipElement(count, changed))
            } else {
                Column(spacing = 4) {
                    val currentOrder = if (mode == Mode.Reorder && changed) reversed else order
                    for (identity in currentOrder) item(identity, changed)
                }
            }
        }

    private fun UiScope.item(
        identity: Int,
        changed: Boolean,
    ) {
        val modifier = if (mode == Mode.Bounds && changed) Modifier.Empty.size(120, 24) else Modifier.Empty.size(100, 20)
        val key = ElementKey(identity)
        when (mode) {
            Mode.Button -> Button("Item $identity", enabled = (identity == 0 && changed).not(), modifier = modifier, key = key)
            Mode.Progress -> ProgressBar(if (identity == 0 && changed) 0.75 else 0.25, IntSize(100, 20), modifier = modifier, key = key)
            else -> Text(if (changed && (mode == Mode.Full || (mode == Mode.Localized && identity == 0))) "Changed $identity" else "Item $identity", modifier = modifier, key = key)
        }
    }

    override fun close() {
        val previous = host
        host = null
        initialElements = emptyList()
        try {
            previous?.close()
            check(root.hasChildNodes().not()) { "DOM fixture close retained native children" }
        } finally {
            container.parentNode?.removeChild(container)
        }
    }

    /**
     * Owns the supplemental browser protocol without adding it to a shared fixture dispatcher or registry.
     */
    companion object {
        /**
         * Exposes compiled metadata, untimed work operations and shared-kit measurement to an independent collector driver.
         * Native objects remain on this page's browser agent and an open work probe must close before another begins.
         */
        fun protocol(
            theme: WebTheme,
        ): dynamic {
            var work: WebDomPerformanceFixture? = null
            return json(
                "inventory" to { inventory() },
                "openWork" to {
                    check(work == null) { "DOM work probe is already open" }
                    val fixture = selected(theme)
                    work = fixture
                    fixture.mount()
                    fixture.root
                },
                "publishWork" to { checkNotNull(work).publish(0) },
                "applyWork" to { checkNotNull(work).apply() },
                "verifyWork" to { checkNotNull(work).verifyWork() },
                "closeWork" to {
                    val previous = work
                    work = null
                    previous?.close()
                },
                "measure" to { phase: String ->
                    measureSelected(theme, phase)
                },
                "verifyAll" to { verifyAll(theme) },
            )
        }

        private fun measureSelected(
            theme: WebTheme,
            phase: String,
        ): Promise<String> {
            val fixture = selected(theme)
            require(PerformancePhase.valueOf(phase) == fixture.mode.phase)
            runCatching { fixture.mount() }.getOrElse { failure ->
                runCatching { fixture.close() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
                throw failure
            }
            return fixture.measure()
        }

        private fun verifyAll(
            theme: WebTheme,
        ): Boolean {
            for (mode in Mode.entries) {
                for (count in mode.counts) {
                    WebDomPerformanceFixture(theme, mode, count).use { fixture ->
                        fixture.mount()
                        fixture.publish(0)
                        fixture.apply()
                        fixture.verifyWork()
                    }
                }
            }
            return true
        }

        private fun inventory(): String {
            val cases =
                Mode.entries.flatMap { mode ->
                    mode.counts.map { count -> json("mode" to mode.name, "count" to count, "phase" to mode.phase.name) }
                }.toTypedArray()
            return JSON.stringify(
                json(
                    "schema" to "strata-dom-writes-v1",
                    "publication" to "before-operation-clock",
                    "operation" to "synchronous-WebUiHost.render",
                    "cases" to cases,
                ),
            )
        }

        private fun selected(
            theme: WebTheme,
        ): WebDomPerformanceFixture {
            val parameters = URLSearchParams(window.location.search)
            val mode = Mode.valueOf(checkNotNull(parameters.get("strata-dom-mode")))
            val count = checkNotNull(parameters.get("strata-dom-count")?.toIntOrNull())
            require(count in mode.counts) { "DOM workload count is not admitted by compiled metadata" }
            return WebDomPerformanceFixture(theme, mode, count)
        }

        private fun elements(root: HTMLElement): List<HTMLElement> = (0 until root.children.length).map { root.children.item(it) as HTMLElement }

        private fun properties(element: HTMLElement): List<Any?> {
            val style = element.style
            val styles = (0 until style.length).map(style::item).associateWith(style::getPropertyValue)
            return listOf(element.tagName, element.getAttribute("data-strata-theme"), styles, element.textContent, (element as? HTMLButtonElement)?.type, (element as? HTMLButtonElement)?.disabled, (element as? HTMLProgressElement)?.value, (element as? HTMLProgressElement)?.max)
        }
    }

    /**
     * Fixture-local compiled workloads; the large-screen matrix is separate from unchanged shipped controls.
     */
    private enum class Mode(
        val counts: List<Int>,
        val phase: PerformancePhase = PerformancePhase.Update,
    ) {
        Localized(listOf(1, 100, 1_000)),
        Full(listOf(1, 100, 1_000)),
        Bounds(listOf(100)),
        Clip(listOf(100)),
        Resize(listOf(100), PerformancePhase.Resize),
        Reorder(listOf(100)),
        Button(listOf(100)),
        Progress(listOf(100)),
    }

    /**
     * Paint-only clipping changes over a fixed complete decorative surface using the public extension SPI.
     */
    private class ClipElement(
        val count: Int,
        val active: Boolean,
    ) : Element(ElementIdentity.Positional, TYPE) {
        private class Node(
            var count: Int,
            var active: Boolean,
        ) : RetainedNode(),
            MeasureNode,
            PaintNode {
            override fun measure(
                scope: MeasureScope,
                constraints: Constraints,
            ): IntSize = constraints.constrain(IntSize(320, count * 32 + 40))

            override fun paint(
                scope: PaintScope,
            ) {
                scope.withClip(IntRect(if (active) 10 else 0, 0, 80, count * 32 + 40)) {
                    for (index in 0 until count) scope.fillRectangle(IntRect(0, index * 24, 100, index * 24 + 20), ArgbColor(0xff808040.toInt()))
                }
            }
        }

        companion object {
            private val TYPE =
                ElementType(
                    elementClass = ClipElement::class,
                    nodeClass = Node::class,
                    validateLocal = { _ -> },
                    createNode = { Node(it.count, it.active) },
                    updateNode = { previous, current, node ->
                        node.count = current.count
                        node.active = current.active
                        if (previous.count == current.count && previous.active == current.active) DirtyMask.None else DirtyMask.of(DirtyPhase.Paint)
                    },
                )
        }
    }
}
