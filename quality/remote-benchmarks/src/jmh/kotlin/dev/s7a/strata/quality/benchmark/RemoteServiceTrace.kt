@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Base64

/**
 * Detached complete gate or work prefix; Throwable references support in-process identity assertions only.
 * Work sequences have no gate case, and retain their actual input topology independently.
 */
internal class RemoteServiceTrace(
    val case: RemoteServiceGate.Case? = null,
    val topology: RemoteServiceShape = checkNotNull(case).topology,
    val workSequence: WorkSequence? = null,
) {
    val events = mutableListOf<List<Any?>>()
    val messages = mutableListOf<List<Any?>>()
    val packets = mutableListOf<List<Any?>>()
    val routes = mutableListOf<Any>()
    val failures = mutableListOf<List<Any?>>()
    val phases = mutableListOf<List<Any?>>()
    val verificationFailures = mutableListOf<List<Any?>>()
    var collectedOwners: Int? = null

    @Transient
    val throwables = mutableListOf<Throwable>()
    val prefix = mutableListOf<Any>()

    // Category/index pairs order the exact stream rows without duplicating their encoded payloads.
    val ordered = mutableListOf<List<Any?>>()
    val phaseEncoding = PhaseEncoding.HandleDeltas

    @Transient
    private val currentHandles = linkedMapOf<Long, List<Any?>>()
    var addresses: Any? = null
    var work: List<Long> = emptyList()

    @Transient
    private val codec = RemoteMessageCodec()

    /**
     * Records exact lifecycle encounter order and detached public values, without retaining an owner/session.
     */
    fun event(
        player: Int,
        event: RemoteLifecycleEvent<Any>,
    ) {
        events +=
            when (event) {
                is RemoteLifecycleEvent.Ready -> {
                    listOf(
                        player,
                        "Ready",
                        event.capabilities.hudLimit,
                        event.capabilities.types
                            .map { it.toString() }
                            .sorted(),
                    )
                }

                is RemoteLifecycleEvent.Disconnected -> {
                    listOf(player, "Disconnected", event.reason)
                }

                is RemoteLifecycleEvent.Opened -> {
                    listOf(player, "Opened", event.identity, event.presentation, event.session.status.toString())
                }

                is RemoteLifecycleEvent.PresentationChanged -> {
                    listOf(player, "PresentationChanged", event.identity, event.previous, event.presentation, event.session.status.toString())
                }

                is RemoteLifecycleEvent.Closed -> {
                    listOf(player, "Closed", event.identity, event.presentation, event.reason, event.session.status.toString())
                }
            }
        ordered += listOf(Observation.Event, events.lastIndex)
    }

    /**
     * Encodes the actually decoded logical message, including identity, generation, order and every field.
     */
    fun message(
        player: Int,
        message: RemoteMessage,
    ) {
        val bytes = codec.encode(message)
        messages += listOf(player, message.javaClass.simpleName, message.session, bytes.size, Base64.getEncoder().encodeToString(bytes))
        ordered += listOf(Observation.Message, messages.lastIndex)
    }

    /**
     * Records detached current work and exact changes to public handle status/source subscriptions.
     * Deltas bound stored state by actual changes; unchanged handles are reconstructed from prior rows.
     * Changed/new rows are in actual encounter order, with removals and an explicit order override when needed.
     */
    fun phase(fleet: RemoteServiceFleet) {
        val current =
            fleet.handles.values.associate { handle ->
                handle.identity to listOf(handle.status.toString(), handle.uiSession.status.toString(), checkNotNull(fleet.sources[handle.identity]).subscriptions)
            }
        val removed = currentHandles.keys.filter { (it in current).not() }
        val changed = current.filter { (identity, values) -> currentHandles[identity] != values }.map { (identity, values) -> listOf(identity, values) }
        val reconstructed = currentHandles.keys.filter { it in current } + current.keys.filter { (it in currentHandles).not() }
        val actualOrder = current.keys.toList()
        val orderOverride = if (reconstructed == actualOrder) emptyList() else actualOrder
        phases += listOf(fleet.ticks, fleet.publications, fleet.evaluations, fleet.peers.keys.toList(), changed, removed, orderOverride)
        currentHandles.clear()
        currentHandles.putAll(current)
        ordered += listOf(Observation.Phase, phases.lastIndex)
    }

    /**
     * Retains a failed verifier's observed prefix without treating it as a successful gate receipt.
     */
    fun verificationFailure(failure: Throwable) {
        verificationFailures += listOf(failure.javaClass.name, failure.message, failure.suppressed.map { listOf(it.javaClass.name, it.message) })
        ordered += listOf(Observation.VerificationFailure, verificationFailures.lastIndex)
    }

    /**
     * Records successful output writes and input enqueue attempts with their real envelope identity/order.
     * Actual random routes are preserved separately; payload rows refer to their invocation-local indexes.
     */
    fun packet(
        player: Int,
        direction: RemoteServiceFleet.Direction,
        packet: RemotePacket,
    ) {
        when (packet) {
            RemotePacket.Discovery -> {
                packets += listOf(player, direction, "Discovery")
            }

            is RemotePacket.Frame -> {
                var route = routes.indexOf(packet.address)
                if (route < 0) {
                    route = routes.size
                    routes += packet.address
                }
                packets += listOf(player, direction, route, packet.sequence, packet.bytes.size, Base64.getEncoder().encodeToString(packet.bytes))
            }
        }
        ordered += listOf(Observation.Packet, packets.lastIndex)
    }

    /**
     * Preserves observed primary/suppressed exception order and original in-process identity.
     */
    fun failure(failure: Throwable) {
        throwables += failure
        failures += listOf(failure.javaClass.name, failure.message, failure.suppressed.map { listOf(it.javaClass.name, it.message) })
        ordered += listOf(Observation.Failure, failures.lastIndex)
    }

    /**
     * Source-bound untimed work sequence, independent of the callback/failure registry.
     */
    enum class WorkSequence {
        Steady,
        Lifecycle,
    }

    /**
     * Phase rows apply removals, then changed/new handle rows; a nonempty final vector replaces order.
     * The first row starts from an empty detached handle map and every subsequent row is fully replayable.
     */
    enum class PhaseEncoding {
        HandleDeltas,
    }

    /**
     * Typed record category and zero-based stream index preserve the actual combined encounter order.
     */
    private enum class Observation {
        Event,
        Message,
        Phase,
        VerificationFailure,
        Packet,
        Failure,
    }
}
