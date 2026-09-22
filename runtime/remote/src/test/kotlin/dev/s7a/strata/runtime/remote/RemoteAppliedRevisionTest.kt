@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies client installation acknowledgements over the wire independently of business-action confirmations.
 */
internal class RemoteAppliedRevisionTest {
    @Test
    fun installedRevisionsAreAcknowledgedMonotonicallyAndFutureConfirmationsFail() {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val codec = RemoteMessageCodec()
        val state = mutableStateOf(ArgbColor(-65536))
        val outgoing = ArrayDeque<RemoteMessage>()
        val confirmations = ArrayDeque<RemoteMessage>()
        RemoteServerSession(1, ProjectionValue.Absent, registry.types, send = outgoing::add) {
            evaluateComponentTree { Spacer(Modifier.Empty.background(state.value)) }
        }.use { server ->
            server.tick()
            RemoteClientSession(outgoing.removeFirst() as RemoteMessage.Snapshot, registry, send = { confirmations.add(codec.decode(codec.encode(it))) }).use { client ->
                assertEquals(0, server.appliedRevision)
                val initial = confirmations.removeFirst()
                assertEquals(RemoteMessage.Applied(1, 1), initial)
                server.receive(initial)
                state.value = ArgbColor(-16776961)
                server.tick()
                client.receive(outgoing.removeFirst() as RemoteMessage.Update)
                assertEquals(1, server.appliedRevision)
                server.receive(confirmations.removeFirst())
                server.receive(initial)
                assertEquals(2, server.appliedRevision)
                assertThrows(RemoteProtocolException::class.java) { server.receive(RemoteMessage.Applied(1, 3)) }
                assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), server.status)
            }
        }
    }
}
