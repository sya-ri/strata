package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.messages.ChannelMessageSource
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Actual public callback matrix and independent source/authentication/ownership controls.
 */
internal class NativeRoutingTest {
    @Test
    fun everyHeaderAndCallbackControlKeepsAllThreeActualRoutingDecisions() {
        NativeRoutingDirection.entries.forEach { direction ->
            NativeRoutingWorkload.entries.forEach { workload ->
                NativeRoutingFixture(1, workload, direction).use { fixture ->
                    fixture.prepare()
                    fixture.callback()
                    fixture.verifyCallback()
                    fixture.finish()
                }
            }
        }
    }

    @Test
    fun manyAuthenticatedPlayersRetainIndependentRoutesAndCompleteLargeTransfers() {
        listOf(NativeRoutingWorkload.SmallControl, NativeRoutingWorkload.MultiFragment, NativeRoutingWorkload.OneMiB).forEach { workload ->
            NativeRoutingDirection.entries.forEach { direction ->
                NativeRoutingFixture(8, workload, direction).use { fixture ->
                    repeat(2) {
                        fixture.prepare()
                        fixture.callback()
                        fixture.verifyCallback()
                        fixture.finish()
                    }
                }
            }
        }
    }

    @Test
    fun proxyInboxStillDetachesARealCallbackAndRetiredBackendsCannotUseStructuralEquality() {
        NativeRoutingFixture(1, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientProxy).use { fixture ->
            fixture.prepare()
            val actor = fixture.actors.single()
            val bytes = NativeRoutingInputs.packets(0, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientProxy).single()
            val expected = bytes.copyOf()
            val event = PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, bytes)
            fixture.plugin.message(event)
            bytes.fill(0)
            fixture.onOwner {
                val snapshot = checkNotNull(fixture.inbox(0).poll())
                assertNotSame(bytes, snapshot)
                assertArrayEquals(expected, snapshot)
            }
            assertTrue(actor.backendWrites.isEmpty())
            assertTrue(actor.clientWrites.isEmpty())
        }
        NativeRoutingFixture(1, NativeRoutingWorkload.StaleBackend, NativeRoutingDirection.BackendClient).use { fixture ->
            fixture.prepare()
            val actor = fixture.actors.single()
            val current = checkNotNull(actor.current.get())
            assertEquals(current, actor.backend)
            assertNotSame(current, actor.backend)
            fixture.callback()
            val counts = fixture.verifyCallback()
            assertEquals(0L, counts.getValue("forwarded_packets"))
            fixture.finish()
        }
    }

    @Test
    fun callbackWriteFailureKeepsItsIdentityAndHandledTransitionAndReplacementRoutesAreReadPerCall() {
        NativeRoutingFixture(1, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientBackend).use { fixture ->
            fixture.prepare()
            val actor = fixture.actors.single()
            val failure = IllegalStateException("native writer")
            actor.writeFailure = failure
            val bytes = NativeRoutingInputs.packets(0, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientBackend).single()
            val event = PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, bytes)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.plugin.message(event) })
            assertFalse(event.result.isAllowed)
            actor.writeFailure = null
            actor.current.set(null)
            assertEquals(null, fixture.plugin.message(PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, bytes)))
            assertTrue(actor.backendWrites.isEmpty())
            val replacement = actor.replacement()
            actor.current.set(replacement)
            fixture.plugin.message(PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, bytes))
            assertArrayEquals(bytes, actor.backendWrites.single())
            assertSame(replacement, actor.backendDestinations.single())
        }
    }

    @Test
    fun terminalShutdownReleasesQueuedSnapshotsAndSubsequentCallbacksStayHandledWithoutRouting() {
        NativeRoutingFixture(1, NativeRoutingWorkload.MultiFragment, NativeRoutingDirection.ClientProxy).use { fixture ->
            fixture.prepare()
            fixture.callback()
            fixture.verifyCallback()
            val inbox = fixture.inbox(0)
            val actor = fixture.actors.single()
            fixture.close()
            assertEquals(null, inbox.poll())
            val bytes = NativeRoutingInputs.packets(0, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientBackend).single()
            listOf<ChannelMessageSource>(actor.player, actor.backend).forEach { source ->
                val event = PluginMessageEvent(source, actor.player, VelocityScreenService.CHANNEL, bytes)
                assertEquals(null, fixture.plugin.message(event))
                assertFalse(event.result.isAllowed)
            }
            assertTrue(actor.clientWrites.isEmpty())
            assertTrue(actor.backendWrites.isEmpty())
        }
    }

    @Test
    fun concurrentCallbacksRemainSerializedAndNeverForwardProxyDestinationsToABackend() {
        NativeRoutingFixture(1, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientBackend).use { fixture ->
            fixture.prepare()
            val actor = fixture.actors.single()
            val executor = Executors.newFixedThreadPool(4)
            try {
                val packets =
                    (1L..64L).map { identity ->
                        ByteBuffer
                            .allocate(43)
                            .put(1.toByte())
                            .put(RemoteEndpoint.Server.ordinal.toByte())
                            .putLong(0)
                            .putLong(identity)
                            .putLong(identity)
                            .put(ByteArray(17))
                            .array()
                    }
                val calls =
                    packets.map { bytes ->
                        executor.submit {
                            val event = PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, bytes)
                            fixture.plugin.message(event)
                            assertFalse(event.result.isAllowed)
                        }
                    }
                calls.forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(packets.size, actor.backendWrites.size)
                val identities = actor.backendWrites.map { ByteBuffer.wrap(it).getLong(10) }.toSet()
                assertEquals((1L..64L).toSet(), identities)
                val proxy = NativeRoutingInputs.packets(0, NativeRoutingWorkload.SmallControl, NativeRoutingDirection.ClientProxy).single()
                fixture.plugin.message(PluginMessageEvent(actor.player, actor.backend, VelocityScreenService.CHANNEL, proxy))
                assertEquals(packets.size, actor.backendWrites.size)
                fixture.onOwner { assertArrayEquals(proxy, checkNotNull(fixture.inbox(0).poll())) }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
