@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import java.util.IdentityHashMap

/**
 * Current-projection image encodings confined to one server session and its construction execution owner.
 * Keys are exact immutable image identities under the session's fixed limits; content equality never joins keys.
 * The current/pending union charges source and encoded pixel payloads against the pending-byte bound and distinct membership against the declaration bound.
 * Unadmitted values are encoded normally without being retained.
 * Commit retires absent images; failed projection and terminal close release both maps before user callbacks.
 */
internal class RemoteServerImages(
    private val limits: RemoteLimits,
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    private val codec = RemoteImageCodec(limits.messageBytes)
    private var phase = Phase.Idle
    private var current = IdentityHashMap<DrawImage, Snapshot>()
    private var pending = IdentityHashMap<DrawImage, Snapshot>()
    private var retainedBytes = 0L
    private var retainedEntries = 0

    /**
     * Starts the next complete declaration cutoff while preserving reusable current identities.
     */
    fun begin() {
        checkOwner()
        check(phase == Phase.Idle) { "Image projection cannot reenter or begin after close." }
        phase = Phase.Projecting
        check(pending.isEmpty())
        retainedEntries = current.size
        retainedBytes = if (current.isEmpty()) 0L else current.values.sumOf { it.bytes }
    }

    /**
     * Returns detached pixels for one immutable identity, reusing only an admitted current or pending snapshot.
     * Both payloads are charged once for shared current/pending membership; ordinary codec bounds still apply.
     */
    fun project(image: DrawImage): ProjectionValue {
        checkOwner()
        check(phase == Phase.Projecting) { "Images require an active declaration projection." }
        val retained = pending[image] ?: current[image]
        if (retained != null) {
            pending[image] = retained
            return retained.value
        }
        val value = codec.encode(ImageSource.Pixels(image))
        val bytes = image.size.width.toLong() * image.size.height * Int.SIZE_BYTES * 2
        if (retainedEntries < limits.collectionEntries && bytes <= limits.pendingBytes - retainedBytes) {
            pending[image] = Snapshot(value, bytes)
            retainedEntries++
            retainedBytes += bytes
        }
        return value
    }

    /**
     * Publishes only the completed projection's admitted identities and retires unrelated history.
     */
    fun commit() {
        checkOwner()
        check(phase == Phase.Projecting) { "No image projection is pending." }
        val previous = current
        current = pending
        pending = previous
        pending.clear()
        retainedEntries = current.size
        retainedBytes = if (current.isEmpty()) 0L else current.values.sumOf { it.bytes }
        phase = Phase.Idle
    }

    /**
     * Clears every key/value before terminal callbacks; repeated close cannot reopen admission.
     */
    override fun close() {
        checkOwner()
        if (phase == Phase.Closed) return
        phase = Phase.Closed
        current.clear()
        pending.clear()
        current = IdentityHashMap()
        pending = IdentityHashMap()
        retainedEntries = 0
        retainedBytes = 0
    }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() == owner) { "Image projection belongs to another execution owner." }
    }

    private enum class Phase { Idle, Projecting, Closed }

    /**
     * One exclusively derived immutable encoded value and the charge for source plus encoded pixel payloads.
     */
    private class Snapshot(
        val value: ProjectionValue,
        val bytes: Long,
    )
}
