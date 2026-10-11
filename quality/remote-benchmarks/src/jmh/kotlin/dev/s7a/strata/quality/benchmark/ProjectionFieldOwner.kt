@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiInputPolicy

/**
 * One actual retained remote owner with two nodes, one current outbound message and one external source subscription.
 * Construction, projection, control requests and release stay on the same JMH worker's execution owner.
 */
internal class ProjectionFieldOwner(
    private val inputs: Inputs,
) : AutoCloseable {
    private val session =
        RemoteServerSession(1, ProjectionValue.Absent, inputs.types, send = inputs.send, content = inputs.content)

    init {
        session.tick()
    }

    /**
     * Reads the unchanged retained declaration without publishing another source value.
     */
    fun idle(): Int {
        session.tick()
        return session.nodeCount
    }

    /**
     * Publishes a scalar progress revision and projects the real source cutoff and declaration patch.
     */
    fun update(): Int {
        inputs.publish()
        session.tick()
        return session.nodeCount
    }

    /**
     * Changes a scalar input policy through the actual public controller before the declaration tick.
     */
    fun control(): Int {
        val policy = if (session.uiSession.inputPolicy == UiInputPolicy.BlockAll) UiInputPolicy.Movement else UiInputPolicy.BlockAll
        session.uiSession.setInputPolicy(policy)
        session.tick()
        return session.nodeCount
    }

    /**
     * Checks bounded current ownership outside timing, including the real declaration and subscription count.
     */
    fun verifyOpen() {
        check(session.status == RemoteSessionStatus.Open && session.nodeCount == 2)
        check(inputs.source.subscriptions == 1 && inputs.latest != null)
    }

    /**
     * Checks terminal retained release while this closed handle and its caller-owned inputs remain reachable.
     */
    fun verifyClosed() {
        check(session.status is RemoteSessionStatus.Closed && session.nodeCount == 0)
        check(inputs.source.subscriptions == 0 && inputs.latest == null)
    }

    override fun close() {
        try {
            session.close()
        } finally {
            inputs.latest = null
        }
    }

    /**
     * Independent immutable callbacks and caller-owned source prepared before owner timing.
     * Only the current outbound message is retained; lifecycle iterations reuse no retained owner or subscription.
     */
    class Inputs {
        val source = RemoteStateSource(0)
        val types = RemoteRegistry().also(RemoteBuiltins::register).types
        var latest: RemoteMessage? = null
        var emissions: Long = 0
            private set
        private var revision = 0
        private val runtime = RemoteComponentRuntime()
        val send: (RemoteMessage) -> Unit = { message ->
            latest = message
            emissions += 1
        }
        val content = {
            runtime.evaluate {
                Observe(source) { value -> ProgressBar(if (value % 2 == 0) 0.25 else 0.75) }
            }
        }

        /**
         * Includes actual source snapshot allocation and publication in the update operation.
         */
        fun publish() {
            source.publish(++revision)
        }
    }
}
