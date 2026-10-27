@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.TooltipModifier
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises negotiated auxiliary roots through real encoded snapshots, patches and client reconstruction.
 */
internal class RemoteTooltipTest {
    @Test
    fun hiddenObservedContentCrossesTheTransportAndUpdatesTheRetainedClientTooltip() {
        val source = Source()
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val codec = RemoteMessageCodec()
        val messages = ArrayList<RemoteMessage>()
        val runtime = RemoteComponentRuntime()
        val decorationPolicies = ArrayList<Boolean>()
        val clientRuntime = clientRuntime(decorationPolicies)
        val server =
            RemoteServerSession(1, ProjectionValue.Text("tooltip"), registry.types, send = { messages.add(codec.decode(codec.encode(it))) }) {
                tooltipDefinition(runtime, source)
            }
        server.use {
            server.tick()
            val initial = messages.removeAt(0) as RemoteMessage.Snapshot
            assertTrue(
                initial.tree.nodes.values
                    .any { it.overlays.isNotEmpty() },
            )
            RemoteClientSession(initial, registry, send = {}).use { client ->
                val content = client.definition(UiText.Literal("tooltip")).transfer().content
                createRuntimeUiSession { ComponentRuntimeBridge.evaluate(clientRuntime, content) }.use { host ->
                    host.attach()
                    val constraints = Constraints.fixed(40, 30)
                    assertTrue(host.frame(constraints).drawCommands.isEmpty())
                    source.publish(9)
                    server.tick()
                    messages.filterIsInstance<RemoteMessage.Update>().forEach(client::receive)
                    messages.clear()
                    host.frame(constraints)
                    host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                    val command =
                        host
                            .frame(constraints)
                            .drawCommands
                            .filterIsInstance<DrawCommand.FillRectangle>()
                            .single()
                    assertEquals(ArgbColor(9), command.color)
                    assertEquals(IntRect(0, 8, 10, 14), command.bounds)
                    assertTrue(decorationPolicies.isNotEmpty())
                    assertFalse(decorationPolicies.any { it })
                    source.publish(11)
                    server.tick()
                    messages.filterIsInstance<RemoteMessage.Update>().forEach(client::receive)
                    assertEquals(
                        ArgbColor(11),
                        host
                            .frame(constraints)
                            .drawCommands
                            .filterIsInstance<DrawCommand.FillRectangle>()
                            .single()
                            .color,
                    )
                }
            }
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun missingTooltipCapabilityIsRejectedBeforeSendingATree() {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val messages = ArrayList<RemoteMessage>()
        val runtime = RemoteComponentRuntime()
        RemoteServerSession(1, ProjectionValue.Text("tooltip"), registry.types - RemoteProfileComponent.ContentTooltip.type, send = messages::add) {
            runtime.evaluate { Spacer(Modifier.tooltip { Spacer() }) }
        }.use { server ->
            val failure = assertThrows(RemoteProtocolException::class.java, server::tick)
            assertEquals(RemoteFailure.UnsupportedType, failure.reason)
            assertTrue(messages.none { it is RemoteMessage.Snapshot })
        }
    }

    /**
     * Isolates transport reconstruction from platform decoration and resource lookup.
     */
    private fun clientRuntime(decorationPolicies: MutableList<Boolean>): ComponentRuntime =
        object : ComponentRuntime by RemoteComponentRuntime() {
            override fun retainEvaluator(): ComponentEvaluator = ComponentEvaluator { ComponentRuntimeBridge.evaluate(this, it) }

            override fun tooltip(
                modifier: Modifier,
                content: Element,
                decorated: Boolean,
                delayMillis: Long,
            ): Modifier {
                decorationPolicies.add(decorated)
                return modifier.then(TooltipModifier(content, delayMillis))
            }
        }

    /**
     * Compiled server declaration whose hidden content observes an independently revisioned source.
     */
    private fun tooltipDefinition(
        runtime: RemoteComponentRuntime,
        source: StateSource<Int>,
    ): Element =
        runtime.evaluate {
            Stack(Modifier.size(40, 30)) {
                Spacer(
                    Modifier.size(4, 4).tooltip(delayMillis = 0, decorated = false) {
                        Observe(source) { color -> Spacer(Modifier.size(10, 6).background(ArgbColor(color))) }
                    },
                )
            }
        }

    /**
     * Single-owner revisioned source with explicit subscription-release evidence.
     */
    private class Source : StateSource<Int> {
        private var revision = 0L
        private var snapshot = StateSnapshot(StateRevision(revision), 7)
        private var observer: ((StateSnapshot<Int>) -> Unit)? = null
        var releases = 0

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(snapshot) {
                this.observer = null
                releases += 1
            }
        }

        /**
         * Publishes one newer immutable observation on the test owner.
         */
        fun publish(value: Int) {
            revision += 1
            snapshot = StateSnapshot(StateRevision(revision), value)
            observer?.invoke(snapshot)
        }
    }
}
