package dev.s7a.strata.integration.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.BrowserPerformanceJson
import dev.s7a.strata.performance.BrowserPerformanceMeter
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.WebUiHost
import dev.s7a.strata.runtime.web.mountWeb
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.Promise

/**
 * One real Web lifecycle fixture; sampling, clocks, aggregation, timeout, and cleanup dispatch belong to the kit.
 */
internal class WebPerformanceFixture(
    private val theme: WebTheme,
    private val phase: PerformancePhase,
) {
    init {
        require(phase != PerformancePhase.Prepare)
    }

    private val container =
        (document.createElement("div") as HTMLElement)
            .apply {
                style.position = "absolute"
                style.left = "0px"
                style.top = "0px"
            }.also { checkNotNull(document.body).appendChild(it) }
    private val root = (document.createElement("div") as HTMLElement).also(container::appendChild)
    private val scenario = ReactiveScenario()
    private var host: WebUiHost? = null
    private var pointerTarget: HTMLButtonElement? = null
    private var pointerEvent: MouseEvent? = null

    /**
     * Transfers fixture cleanup to the kit, including preparation and operation failures.
     */
    fun measure(): Promise<String> =
        BrowserPerformanceMeter()
            .measure(
                ready = { root.isConnected },
                beforeSample = { prepareSample() },
                afterSample = ::verifySample,
                cleanup = ::close,
                operation = ::perform,
            ).then { sample -> BrowserPerformanceJson.sample(sample) }

    private fun mount() {
        host = mountWeb(scenario.definition(), root, ReactiveScenario.viewport, theme)
    }

    private fun prepareSample() {
        if (phase == PerformancePhase.Release || (phase != PerformancePhase.Initial && host == null)) mount()
        if (phase == PerformancePhase.Input) {
            val button = root.querySelectorAll("button").item(2) as HTMLButtonElement
            val bounds = button.getBoundingClientRect()
            pointerTarget = button
            pointerEvent = MouseEvent("pointerdown", MouseEventInit(bubbles = true, cancelable = true, clientX = (bounds.left + bounds.width / 2).toInt(), clientY = (bounds.top + bounds.height / 2).toInt()))
        }
    }

    private fun verifySample(index: Int) {
        if (phase == PerformancePhase.Input) {
            val expected = listOf("Alpha", "Beta")[index % 2]
            check(root.lastElementChild?.textContent == expected) { "Native pointer activation did not reorder keyed nodes" }
        }
        if (phase == PerformancePhase.Initial || phase == PerformancePhase.Release) {
            host?.close()
            host = null
            check(root.hasChildNodes().not())
        }
    }

    private fun perform(index: Int) {
        when (phase) {
            PerformancePhase.Initial -> {
                mount()
            }

            PerformancePhase.Idle -> {
                checkNotNull(host).render(ReactiveScenario.viewport)
            }

            PerformancePhase.Update -> {
                scenario.toggle()
                checkNotNull(host).render(ReactiveScenario.viewport)
            }

            PerformancePhase.Input -> {
                checkNotNull(pointerTarget).dispatchEvent(checkNotNull(pointerEvent))
            }

            PerformancePhase.Resize -> {
                checkNotNull(host).render(IntSize(400 + index % 2, 380 + index % 2))
            }

            PerformancePhase.Release -> {
                checkNotNull(host).close()
            }

            PerformancePhase.Prepare -> {
                error("Preparation is not a timed Web operation")
            }
        }
    }

    private fun close() {
        try {
            host?.close()
        } finally {
            host = null
            pointerTarget = null
            pointerEvent = null
            container.parentNode?.removeChild(container)
        }
    }
}
