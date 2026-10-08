package dev.s7a.strata.integration.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.web.WebTheme
import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.runtime.web.renderWebHtml
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.js.json
import kotlin.js.unsafeCast
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Exercises incremental native DOM ownership against independent fresh mounts in each supported browser.
 * Returned markup is detached screenshot input; all live hosts, subscriptions and DOM children close before return.
 */
internal object WebDomUpdateCheck {
    /**
     * Validates localized and full updates, adoption, geometry, native input, focus, topology and owner isolation.
     * Mutation counts are deterministic work evidence; this method records no timing or allocation estimates.
     */
    fun verify(theme: WebTheme): String = JSON.stringify((listOf(1, 100, 1_000).flatMap { verifySize(it, theme) } + verifyClip(theme)).toTypedArray())

    private fun verifyClip(
        theme: WebTheme,
    ): List<dynamic> {
        val viewport = IntSize(120, 80)
        val initial = ClipInputs(IntRect(5, 7, 85, 47), IntRect(10, 12, 50, 30), ArgbColor(0x80804020.toInt()))
        val state = mutableStateOf(initial)
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        try {
            mountWeb(UiDefinition { element(ClipElement(state.value)) }, root, viewport, theme).use { host ->
                val original = root.firstElementChild
                val changes = listOf(initial.copy(clip = IntRect(20, 10, 90, 50)), initial.copy(bounds = IntRect(9, 11, 99, 51)), initial.copy(color = ArgbColor(-1)))
                val evidence = ArrayList<dynamic>()
                for ((index, changed) in changes.withIndex()) {
                    state.value = changed
                    host.render(viewport)
                    check(root.firstElementChild === original)
                    evidence.add(compareClip(root, changed, theme, index))
                }
                return evidence
            }
        } finally {
            check(root.hasChildNodes().not())
            root.parentNode?.removeChild(root)
        }
    }

    private fun compareClip(
        root: HTMLElement,
        inputs: ClipInputs,
        theme: WebTheme,
        index: Int,
    ): dynamic {
        val reference = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(reference)
        try {
            mountWeb(UiDefinition { element(ClipElement(inputs)) }, reference, IntSize(120, 80), theme).use {
                check(elements(root).map(::properties) == elements(reference).map(::properties)) { "Incremental clip or background differs from fresh rendering" }
                return json("count" to 1, "phase" to "clip-$index", "mutations" to null, "currentHtml" to root.outerHTML, "referenceHtml" to reference.outerHTML, "checks" to json("independent_full_render" to true, "retained_identity" to true))
            }
        } finally {
            reference.parentNode?.removeChild(reference)
        }
    }

    // Keep one mounted owner through the complete transition and failure sequence.
    @Suppress("LongMethod")
    private fun verifySize(
        count: Int,
        theme: WebTheme,
    ): List<dynamic> {
        val initial = Snapshot((0 until count).toList())
        val scenario = Scenario(initial)
        val root = document.createElement("div") as HTMLElement
        val container =
            (document.createElement("div") as HTMLElement).apply {
                style.position = "absolute"
                style.left = "0px"
                style.top = "0px"
            }
        checkNotNull(document.body).appendChild(container)
        container.appendChild(root)
        val viewport = IntSize(320, count * 24 + 20)
        root.innerHTML = renderWebHtml(scenario.definition(), viewport, theme)
        val adopted = elements(root)
        val host = mountWeb(scenario.definition(), root, viewport, theme)
        val observer = MutationObserver { _, _ -> }
        val evidence = ArrayList<dynamic>()
        try {
            check(adopted == elements(root)) { "Generated DOM was replaced at $count elements" }
            val button = root.querySelector("button") as HTMLButtonElement
            button.focus()
            check(document.activeElement === button)
            // Omit attributeFilter entirely: a present null value cannot be converted to a native sequence.
            observer.observe(root, json("attributes" to true, "childList" to true, "subtree" to true).unsafeCast<MutationObserverInit>())
            val localized = initial.copy(changed = setOf(count - 1))
            scenario.snapshot.value = localized
            host.render(viewport)
            val records = observer.takeRecords()
            check(records.isNotEmpty() && records.all { it.target === adopted.last() }) { "Localized update mutated another DOM owner" }
            check(records.none { record -> UnchangedAttribute.entries.any { it.token == record.attributeName } }) { "Localized update reassigned unchanged style or identity" }
            check(document.activeElement === button)
            evidence.add(compare(root, localized, viewport, theme, "localized", records.size))

            host.render(viewport)
            check(observer.takeRecords().isEmpty()) { "Idle host mutated its DOM" }
            val all = initial.copy(changed = initial.order.toSet())
            scenario.snapshot.value = all
            host.render(viewport)
            check(elements(root) == adopted)
            evidence.add(compare(root, all, viewport, theme, "all", observer.takeRecords().size))

            val geometry = all.copy(wide = true)
            scenario.snapshot.value = geometry
            val resized = IntSize(360, viewport.height + 10)
            host.render(resized)
            check(elements(root) == adopted)
            evidence.add(compare(root, geometry, resized, theme, "geometry", observer.takeRecords().size))

            scenario.snapshot.value = geometry.copy(order = initial.order.reversed())
            host.render(resized)
            check(elements(root) == adopted.reversed())
            check(document.activeElement === button) { "Reordering untouched focused button lost focus" }
            scenario.snapshot.value = initial.copy(order = initial.order.drop(1))
            host.render(viewport)
            check(adopted.first().parentNode == null)
            scenario.snapshot.value = initial
            host.render(viewport)
            check(elements(root).first() !== adopted.first())

            val currentButton = root.querySelector("button") as HTMLButtonElement
            val bounds = currentButton.getBoundingClientRect()
            val event = MouseEvent("pointerdown", MouseEventInit(bubbles = true, cancelable = true, clientX = (bounds.left + bounds.width / 2).toInt(), clientY = (bounds.top + bounds.height / 2).toInt()))
            currentButton.dispatchEvent(event)
            check(scenario.activations == 1) { "Enabled native button did not activate" }
            observer.takeRecords()
            scenario.snapshot.value = initial.copy(enabled = false)
            host.render(viewport)
            check(currentButton.disabled)
            currentButton.dispatchEvent(event)
            check(scenario.activations == 1) { "Disabled native button activated" }
            evidence.add(compare(root, initial.copy(enabled = false), viewport, theme, "controls", observer.takeRecords().size))

            val original = root.asDynamic().insertBefore
            val expected = IllegalStateException("Injected DOM insertion failure")
            var insertions = 0
            root.asDynamic().insertBefore = { node: dynamic, cursor: dynamic ->
                insertions += 1
                if (insertions == 2) throw expected
                original.call(root, node, cursor)
            }
            try {
                scenario.snapshot.value = initial.copy(order = initial.order + listOf(count, count + 1))
                check(runCatching { host.render(viewport) }.exceptionOrNull() === expected) { "Rendering failure did not preserve the application failure" }
                check(root.hasChildNodes().not()) { "Rendering failure retained a committed or newly inserted element" }
            } finally {
                root.asDynamic().insertBefore = original
            }
        } finally {
            observer.disconnect()
            try {
                host.close()
                check(root.hasChildNodes().not()) { "Closed browser host retained DOM children" }
                check(root.hasAttribute("data-strata-theme-root").not())
            } finally {
                container.parentNode?.removeChild(container)
            }
        }
        evidence.last().checks =
            json(
                "hydration" to true,
                "keyed_reorder" to true,
                "untouched_focus_on_reorder" to true,
                "removed_owner_detached" to true,
                "reinserted_owner_is_fresh" to true,
                "enabled_activation" to true,
                "disabled_activation_rejected" to true,
                "insertion_failure_released" to true,
                "terminal_close" to true,
            )
        return evidence
    }

    private fun compare(
        root: HTMLElement,
        snapshot: Snapshot,
        viewport: IntSize,
        theme: WebTheme,
        phase: String,
        mutations: Int,
    ): dynamic {
        val count = snapshot.order.size
        val reference = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(reference)
        try {
            mountWeb(Scenario(snapshot).definition(), reference, viewport, theme).use {
                check(elements(root).map(::properties) == elements(reference).map(::properties)) { "Incremental DOM differs from full render: $count/$phase" }
                check(root.style.cssText == reference.style.cssText)
                return json("count" to count, "phase" to phase, "mutations" to mutations, "currentHtml" to root.outerHTML, "referenceHtml" to reference.outerHTML)
            }
        } finally {
            reference.parentNode?.removeChild(reference)
        }
    }

    private fun elements(root: HTMLElement): List<HTMLElement> = (0 until root.children.length).map { root.children.item(it) as HTMLElement }

    private fun properties(element: HTMLElement): List<Any?> {
        val style = element.style
        val styles = (0 until style.length).map(style::item).associateWith(style::getPropertyValue)
        return listOf(element.tagName, element.getAttribute("data-strata-theme"), styles, element.textContent, (element as? HTMLButtonElement)?.type, (element as? HTMLButtonElement)?.disabled, (element as? HTMLProgressElement)?.value, (element as? HTMLProgressElement)?.max)
    }

    /**
     * Current immutable test inputs; keyed ordering is independent of the native identity allocated by a fresh mount.
     */
    private data class Snapshot(
        val order: List<Int>,
        val changed: Set<Int> = emptySet(),
        val enabled: Boolean = true,
        val wide: Boolean = false,
    )

    /**
     * Fixed-size native primitives and one caller-owned revision source, without a renderer cache or measurement code.
     */
    private class Scenario(
        initial: Snapshot,
    ) {
        val snapshot = mutableStateOf(initial)
        var activations = 0

        fun definition(): UiDefinition =
            UiDefinition("DOM updates") {
                val current = snapshot.value
                Column(spacing = 4) {
                    for (identity in current.order) {
                        val changed = identity in current.changed
                        val label = if (changed) "After!" else "Before"
                        val modifier = Modifier.Empty.size(if (current.wide) 120 else 100, 20)
                        val key = ElementKey(identity)
                        when (Kind.entries[identity % Kind.entries.size]) {
                            Kind.Button -> Button(label, enabled = current.enabled, modifier = modifier.onActivate { activations += 1 }, key = key)
                            Kind.Progress -> ProgressBar(if (changed) 0.75 else 0.25, IntSize(100, 20), modifier = modifier, key = key)
                            Kind.Text -> Text(label, style = if (changed) TextStyle.Inactive else TextStyle.Normal, modifier = modifier, key = key)
                        }
                    }
                }
            }
    }

    /**
     * Typed primitive selection for deterministic mixed screens.
     */
    private enum class Kind {
        Button,
        Progress,
        Text,
    }

    /**
     * Native attribute names decoded at the mutation-record boundary.
     */
    private enum class UnchangedAttribute(
        val token: String,
    ) {
        Style("style"),
        Identity("data-strata-node"),
        Theme("data-strata-theme"),
    }

    /**
     * Detached decorative geometry, clipping and source color for incremental/full-render parity.
     */
    private data class ClipInputs(
        val bounds: IntRect,
        val clip: IntRect,
        val color: ArgbColor,
    )

    /**
     * Public extension primitive proving that decorative clipped commands retain their ordinary renderer contract.
     */
    private class ClipElement(
        val inputs: ClipInputs,
    ) : Element(ElementIdentity.Positional, TYPE) {
        private class Node(
            var inputs: ClipInputs,
        ) : RetainedNode(),
            MeasureNode,
            PaintNode {
            override fun measure(
                scope: MeasureScope,
                constraints: Constraints,
            ): IntSize = constraints.constrain(IntSize(120, 80))

            override fun paint(
                scope: PaintScope,
            ) {
                scope.withClip(inputs.clip) { scope.fillRectangle(inputs.bounds, inputs.color) }
            }
        }

        companion object {
            private val TYPE =
                ElementType(
                    elementClass = ClipElement::class,
                    nodeClass = Node::class,
                    validateLocal = { _ -> },
                    createNode = { Node(it.inputs) },
                    updateNode = { previous, current, node ->
                        node.inputs = current.inputs
                        if (previous.inputs == current.inputs) DirtyMask.None else DirtyMask.of(DirtyPhase.Paint)
                    },
                )
        }
    }
}
