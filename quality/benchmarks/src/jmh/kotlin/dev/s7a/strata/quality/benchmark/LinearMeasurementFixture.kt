@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Axis
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Bounds
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Operation
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Shape
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Topology
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** Real standard Row/Column, one caller-owned publisher, and one terminal session per fixture owner. */
internal class LinearMeasurementFixture(private val topology: Topology, private val operation: Operation) : AutoCloseable {
    private val source = BenchmarkStateSource(0)
    private var revision = 0
    private var resized = false
    private val session: RuntimeUiSession = createRuntimeUiSession {
        evaluateComponentTree {
            Observe(source) { value ->
                val extent = if (operation == Operation.LayoutOnlyFrame) 1 else value % 2 + 1
                val spacing = if (topology.childCount == 0) extent else 0
                val arrangement = if (operation == Operation.LayoutOnlyFrame && value % 2 == 1) Arrangement.End else Arrangement.Start
                when (topology.axis) {
                    Axis.Row -> Row(key = TARGET, spacing = spacing, horizontalArrangement = arrangement) {
                        repeat(topology.childCount) { index ->
                            val weight = topology.weight(index)
                            val modifier = if (weight == null) Modifier.Empty else Modifier.Empty.weight(weight, topology.shape != Shape.AllNonfill)
                            Spacer(key = ElementKey(index), modifier = modifier.size(extent, 1))
                        }
                    }
                    Axis.Column -> Column(key = TARGET, spacing = spacing, verticalArrangement = arrangement) {
                        repeat(topology.childCount) { index ->
                            val weight = topology.weight(index)
                            val modifier = if (weight == null) Modifier.Empty else Modifier.Empty.weight(weight, topology.shape != Shape.AllNonfill)
                            Spacer(key = ElementKey(index), modifier = modifier.size(1, extent))
                        }
                    }
                }
            }
        }
    }

    /** Attaches and primes outside timing for persistent operations, inside timing for Lifecycle. */
    fun open(): RuntimeUiFrame {
        session.attach()
        return session.frame(constraints())
    }

    /** Publishes or resizes exactly once, then consumes one complete retained frame. */
    fun perform(): RuntimeUiFrame {
        when (operation) {
            Operation.ResizeFrame -> resized = resized.not()
            Operation.SourceFrame, Operation.Lifecycle, Operation.LayoutOnlyFrame -> {
                revision += 1
                source.publish(revision)
            }
            Operation.IdleFrame -> Unit
        }
        return session.frame(constraints())
    }

    /** Independent actual callback admission; diagnostics are never enabled in the timed new corpus. */
    fun verify(): Unit = session.use {
        session.attach()
        session.startRenderMonitoring(65_536).use { monitor ->
            val initial = session.frame(constraints())
            val target = monitor.findNodes(TARGET).single()
            check(monitor.snapshot().overflowed.not())
            check(monitor.snapshot().nodes.single { it.id == target }.counts.getValue(UiRenderMetric.Measure) == 1L)
            if (operation != Operation.Lifecycle) monitor.checkpoint()
            val result = perform()
            val snapshot = monitor.snapshot()
            check(snapshot.overflowed.not())
            val node = snapshot.nodes.single { it.id == target }
            val measures = when (operation) {
                Operation.IdleFrame, Operation.LayoutOnlyFrame -> 0L
                Operation.Lifecycle -> 2L
                Operation.ResizeFrame, Operation.SourceFrame -> 1L
            }
            val layouts = when (operation) {
                Operation.IdleFrame -> 0L
                Operation.Lifecycle -> 2L
                else -> 1L
            }
            check(node.counts.getValue(UiRenderMetric.Measure) == measures) { "No actual linear measurement for $topology/$operation" }
            check(node.counts.getValue(UiRenderMetric.Layout) == layouts)
            check(snapshot.activeSubscriptions == 1 && source.subscribed)
            check(result.drawCommands.isEmpty() && result.semantics.isEmpty())
            check(result.size.width in constraints().minWidth..constraints().maxWidth)
            check(result.size.height in constraints().minHeight..constraints().maxHeight)
            if (operation == Operation.IdleFrame) check(initial === result)
            session.close()
            check(source.subscribed.not())
            check(monitor.snapshot().activeSubscriptions == 0)
            session.close()
        }
    }

    private fun constraints(): Constraints {
        val cross = if (resized) 181 else 180
        return when (topology.bounds) {
            Bounds.Finite -> Constraints.fixed(if (resized) 321 else 320, cross)
            Bounds.IntrinsicMain -> when (topology.axis) {
                Axis.Row -> Constraints(maxHeight = cross)
                Axis.Column -> Constraints(maxWidth = cross)
            }
        }
    }

    /** Releases the terminal owner, including a failed or partially primed session. */
    override fun close() {
        session.close()
        check(source.subscribed.not())
    }

    private enum class Target { Linear }

    private companion object {
        val TARGET = ElementKey(Target.Linear)
    }
}
