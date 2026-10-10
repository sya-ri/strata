package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * One public owner-thread session with topology and stable keys prepared outside steady-frame timing.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class DescriptionValidationFixture(
    private val case: DescriptionValidationCase,
) : AutoCloseable {
    private val state = mutableStateOf(0)
    private val source = BenchmarkStateSource(0)
    private val constraints = Constraints.fixed(320, 180)
    private val membership = List(case.descendants + 1) { ArrayList<Int>() }
    private val identities = Array<ElementIdentity>(case.descendants + 1) { ElementIdentity.Positional }
    private val prepared: Element?
    private var revision = 0
    private val session: RuntimeUiSession

    init {
        for (index in 1..case.descendants) {
            val parent =
                when (case.shape) {
                    DescriptionValidationCase.Shape.Wide -> 0
                    DescriptionValidationCase.Shape.ChainGroups16 -> if ((index - 1) % 16 == 0) 0 else index - 1
                    DescriptionValidationCase.Shape.BalancedBinary -> (index - 1) / 2
                }
            val siblings = membership[parent]
            val keyed =
                when (case.keys) {
                    DescriptionValidationCase.Keys.None -> false
                    DescriptionValidationCase.Keys.All -> true
                    DescriptionValidationCase.Keys.Alternate -> siblings.size % 2 == 1
                }
            if (keyed) identities[index] = ElementIdentity.Keyed(ElementKey(Key(index)))
            siblings.add(index)
        }
        prepared = if (case.route == DescriptionValidationCase.Route.SameDescription || case.route == DescriptionValidationCase.Route.Clean) description(0) else null
        session =
            createRuntimeUiSession {
                when (case.route) {
                    DescriptionValidationCase.Route.RootDefinition -> description(state.value)
                    DescriptionValidationCase.Route.ObserveRebuild ->
                        evaluateComponentTree {
                            Observe(source) { element(description(it % 2)) }
                        }
                    DescriptionValidationCase.Route.SameDescription -> {
                        state.value
                        checkNotNull(prepared)
                    }
                    DescriptionValidationCase.Route.Clean -> checkNotNull(prepared)
                }
            }
        session.attach()
        session.frame(constraints)
    }

    /**
     * Includes actual caller assignment/source publication, declaration construction and a complete retained frame.
     */
    fun nextFrame(): RuntimeUiFrame {
        revision += 1
        when (case.route) {
            DescriptionValidationCase.Route.RootDefinition, DescriptionValidationCase.Route.SameDescription -> state.value = revision % 2
            DescriptionValidationCase.Route.ObserveRebuild -> source.publish(revision)
            DescriptionValidationCase.Route.Clean -> Unit
        }
        return session.frame(constraints)
    }

    /**
     * Checks the real public phase, all placed descendants, equal-result reuse and bounded release outside timing.
     */
    fun verifyWork() {
        RuntimeWorkMonitor(session).use { monitor ->
            repeat(4) {
                monitor.checkpoint()
                val frame = nextFrame()
                val changed = case.route == DescriptionValidationCase.Route.RootDefinition || case.route == DescriptionValidationCase.Route.ObserveRebuild
                check(frame.drawCommands.size == case.descendants + 1)
                check(frame.semantics.size == case.descendants + 1)
                verifyPresentation(frame, if (changed) revision % 2 else 0)
                monitor.verify(
                    WorkExpectation(
                        exact = mapOf(
                            UiRenderMetric.FrameSuccess.name to 1L,
                            UiRenderMetric.RootEvaluation.name to if (case.route == DescriptionValidationCase.Route.RootDefinition || case.route == DescriptionValidationCase.Route.SameDescription) 1L else 0L,
                            UiRenderMetric.ContentEvaluation.name to if (case.route == DescriptionValidationCase.Route.ObserveRebuild) 1L else 0L,
                            UiRenderMetric.NodeUpdate.name to if (changed) case.descendants + 1L else 0L,
                            UiRenderMetric.NodeCreate.name to 0L,
                            UiRenderMetric.NodeDispose.name to 0L,
                            UiRenderMetric.Paint.name to if (changed) case.descendants + 1L else 0L,
                            UiRenderMetric.Semantics.name to if (changed) case.descendants + 1L else 0L,
                        ),
                    ),
                )
                monitor.checkpoint()
                check(session.frame(constraints) === frame)
                monitor.verify(
                    WorkExpectation(exact = mapOf(UiRenderMetric.RootEvaluation.name to 0L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.NodeUpdate.name to 0L)),
                )
            }
        }
        check(source.subscribed == (case.route == DescriptionValidationCase.Route.ObserveRebuild))
    }

    private fun verifyPresentation(frame: RuntimeUiFrame, value: Int) {
        val order = ArrayList<Int>()
        fun visit(index: Int) {
            order.add(index)
            when (case.shape) {
                DescriptionValidationCase.Shape.BalancedBinary -> {
                    for (child in index * 2 + 1..index * 2 + 2) {
                        if (child <= case.descendants) visit(child)
                    }
                }
                DescriptionValidationCase.Shape.Wide, DescriptionValidationCase.Shape.ChainGroups16 -> {
                    if (index == 0) order.addAll(1..case.descendants)
                }
            }
        }
        visit(0)
        check(frame.semantics.map { it.semantics.label } == order.map { UiText.Literal("$it:$value") })
        val rectangle = IntRect(0, 0, value + 1, value + 1)
        val color = ArgbColor(if (value == 0) 0xFF112233.toInt() else 0xFF445566.toInt())
        check(frame.drawCommands.all { it == DrawCommand.FillRectangle(rectangle, color) })
        check(frame.semantics.drop(1).all { it.bounds == rectangle })
    }

    private fun description(value: Int): Element {
        val elements = arrayOfNulls<Element>(case.descendants + 1)
        for (index in case.descendants downTo 0) {
            elements[index] = DescriptionValidationElement(index, value, identities[index], membership[index].map { checkNotNull(elements[it]) })
        }
        return checkNotNull(elements[0])
    }

    override fun close() {
        session.close()
        check(source.subscribed.not())
    }

    /**
     * Stable typed key prepared once rather than publishing changing key identities.
     */
    private data class Key(val index: Int)
}
