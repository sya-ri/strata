package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Real retained remote owners sharing one source, with one current output per peer and no message history.
 * All construction, actions and release run on the caller's actual core execution owner.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RemoteSessionFleet(
    private val workload: RemoteSessionWorkload,
) : AutoCloseable {
    private val source = RemoteStateSource(0)
    private val registry = RemoteRegistry().also(RemoteBuiltins::register)
    private var revision = 0
    val latest: MutableList<RemoteMessage?> = MutableList(workload.sessions) { null }
    var emissions: Long = 0
        private set
    val subscriptions: Int get() = source.subscriptions
    val peers: List<RemoteServerSession>
    val nodeCount: Int get() = peers.sumOf(RemoteServerSession::nodeCount)

    init {
        val limits = RemoteLimits()
        require(limits.treeNodes == 8192 && limits.hudSessions == 16)
        require(workload.sessions * workload.nodes <= limits.treeNodes)
        val acquired = mutableListOf<RemoteServerSession>()
        runCatching {
            repeat(workload.sessions) { peer ->
                val session =
                    RemoteServerSession(peer + 1L, ProjectionValue.Text("Retained remote stress"), registry.types, send = { message ->
                        latest[peer] = message
                        emissions += 1
                    }) {
                        evaluateComponentTree {
                            Observe(source) { value ->
                                // Observe and Column are both real projected records within the declared node budget.
                                Column {
                                    repeat(workload.nodes - 2) { Spacer(Modifier.background(ArgbColor(if (value % 2 == 0) -65536 else -16776961))) }
                                }
                            }
                        }
                    }
                acquired += session
                session.tick()
            }
        }.getOrElse { failure ->
            acquired.asReversed().forEach { peer -> runCatching(peer::close).exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
        peers = acquired.toList()
    }

    /**
     * Projects each real session without source invalidation.
     */
    fun idle(): Int {
        peers.forEach(RemoteServerSession::tick)
        return nodeCount
    }

    /**
     * Publishes once to every active owner and projects the real changed declarations.
     */
    fun update(): Int {
        source.publish(++revision)
        peers.forEach(RemoteServerSession::tick)
        return nodeCount
    }

    /**
     * Untimed admission of terminal source and declaration release while closed handles remain reachable.
     */
    fun verifyClosed() {
        check(subscriptions == 0 && nodeCount == 0)
        check(peers.all { it.status is RemoteSessionStatus.Closed })
    }

    override fun close() {
        var failure: Throwable? = null
        peers.asReversed().forEach { peer ->
            runCatching(peer::close).exceptionOrNull()?.let { current ->
                val previous = failure
                if (previous == null) {
                    failure = current
                } else if (previous !== current) {
                    previous.addSuppressed(current)
                }
            }
        }
        failure?.let { throw it }
    }
}
