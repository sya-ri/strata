@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.quality.benchmark.FlowMeasurementCase.Operation
import dev.s7a.strata.quality.benchmark.FlowMeasurementCase.Topology
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** Standard FlowRow, positive standard painted leaves, and one real publisher/terminal owner per complete operation. */
internal class FlowMeasurementFixture(
    private val topology: Topology,
    private val operation: Operation,
    private val original: Boolean = false,
) : AutoCloseable {
    private val source = BenchmarkStateSource(0)
    private var revision = 0
    private var resized = false
    private val session: RuntimeUiSession =
        createRuntimeUiSession {
            evaluateComponentTree {
                Observe(source) { value ->
                    val extent = if (operation == Operation.LayoutOnlyFrame) 1 else value % 2 + 1
                    val spacing = if (topology.childCount == 0) extent else 1
                    val arrangement = if (operation == Operation.LayoutOnlyFrame && value % 2 == 1) Arrangement.End else Arrangement.Start
                    val children = List(topology.childCount) { index -> child(index, extent) }
                    if (original) {
                        element(FlowReferenceElement(spacing, 1, arrangement, VerticalAlignment.Top, children, TARGET))
                    } else {
                        FlowRow(key = TARGET, horizontalSpacing = spacing, verticalSpacing = 1, horizontalArrangement = arrangement) {
                            children.forEach { element(it) }
                        }
                    }
                }
            }
        }

    private fun child(
        index: Int,
        height: Int,
    ): Element =
        evaluateComponentTree {
            Spacer(key = ElementKey(index), modifier = Modifier.Empty.size(topology.width(index), height).background(COLOR))
        }

    /** Attaches and primes persistent sessions outside timing; Lifecycle performs this inside each invocation. */
    fun open(): RuntimeUiFrame {
        session.attach()
        return session.frame(constraints())
    }

    /** Publishes or resizes once and consumes one complete retained frame. */
    fun perform(): RuntimeUiFrame {
        when (operation) {
            Operation.ResizeFrame -> {
                resized = resized.not()
            }

            Operation.SourceFrame, Operation.Lifecycle, Operation.LayoutOnlyFrame -> {
                revision += 1
                source.publish(revision)
            }

            Operation.IdleFrame -> {}
        }
        return session.frame(constraints())
    }

    /** Checks actual target phase work and full ordered geometry against a separate original retained session. */
    fun verify() {
        check(original.not())
        FlowMeasurementFixture(topology, operation, original = true).use { reference ->
            reference.open().also { expected ->
                session.attach()
                session.startRenderMonitoring(65_536).use { monitor ->
                    val initial = session.frame(constraints())
                    sameFrame(expected, initial)
                    val target = monitor.findNodes(TARGET).single()
                    if (operation != Operation.Lifecycle) monitor.checkpoint()
                    val result = perform()
                    sameFrame(reference.perform(), result)
                    val snapshot = monitor.snapshot()
                    check(snapshot.overflowed.not())
                    val node = snapshot.nodes.single { it.id == target }
                    val measures =
                        when (operation) {
                            Operation.IdleFrame, Operation.LayoutOnlyFrame -> 0L
                            Operation.Lifecycle -> 2L
                            Operation.ResizeFrame, Operation.SourceFrame -> 1L
                        }
                    val layouts =
                        when (operation) {
                            Operation.IdleFrame -> 0L
                            Operation.Lifecycle -> 2L
                            else -> 1L
                        }
                    check(node.counts.getValue(UiRenderMetric.Measure) == measures)
                    check(node.counts.getValue(UiRenderMetric.Layout) == layouts)
                    check(snapshot.activeSubscriptions == 1 && source.subscribed)
                    val rectangles = result.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
                    check(rectangles.size == topology.childCount)
                    check(rectangles.all { 0 < it.bounds.width && 0 < it.bounds.height && it.color == COLOR })
                    check(result.drawCommands.size == rectangles.size && result.semantics.isEmpty())
                    if (operation == Operation.IdleFrame) check(initial === result)
                    session.close()
                    check(source.subscribed.not())
                    check(monitor.snapshot().activeSubscriptions == 0)
                    session.close()
                }
            }
        }
    }

    private fun sameFrame(
        expected: RuntimeUiFrame,
        actual: RuntimeUiFrame,
    ) {
        check(expected.size == actual.size)
        check(expected.drawCommands == actual.drawCommands) { "Original ordered geometry changed for $topology/$operation" }
        check(expected.semantics == actual.semantics)
    }

    private fun constraints(): Constraints =
        Constraints(
            maxWidth = if (resized) topology.resizedMaximumWidth else topology.maximumWidth,
            maxHeight = if (resized) 181 else 180,
        )

    /** Releases the real source callback and terminal owner, including failed or partially primed operations. */
    override fun close() {
        session.close()
        check(source.subscribed.not())
    }

    private enum class Target { Flow }

    private companion object {
        val TARGET = ElementKey(Target.Flow)
        val COLOR = ArgbColor(0xFF1479B8.toInt())
    }
}
